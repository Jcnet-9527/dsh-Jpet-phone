from __future__ import annotations
import json
from datetime import datetime
from pathlib import Path
from .models import ChatMessage, ChatSettings

def load_character_manifest(root:Path,character_id:str):
    try: raw=json.loads((Path(root)/character_id/'manifest.json').read_text(encoding='utf-8'))
    except (OSError,ValueError): return {}
    return raw if isinstance(raw,dict) else {}

def load_character_prompt(root:Path,character_id:str):
    chat=load_character_manifest(root,character_id).get('chat',{})
    return str(chat.get('system_prompt','')) if isinstance(chat,dict) else ''

_WEEKDAYS='一二三四五六日'
_TIME_NOTE=('【时间信息】当前时间：{now}。{elapsed}对话记录中用户消息开头的「[日期 时间]」是系统自动附加的发送时间，'
    '用来帮助你感知时间的流逝（例如隔了多久没聊、现在是白天还是深夜、日期是否变了），回复时自然地体现即可；'
    '不要在回复中复述或模仿这种时间标记。')

def _local_time(iso):
    """created_at（ISO 字符串，通常为 UTC）→ 本地时区 datetime；无法解析返回 None。"""
    try: dt=datetime.fromisoformat(str(iso))
    except (TypeError,ValueError): return None
    return dt.astimezone()  # naive 视为本地时间

def format_time(dt): return f"{dt:%Y-%m-%d} 周{_WEEKDAYS[dt.weekday()]} {dt:%H:%M}"

def format_elapsed(seconds):
    minutes=int(seconds//60); days,rest=divmod(minutes,1440); hours,mins=divmod(rest,60)
    if days: return f'{days} 天' + (f' {hours} 小时' if hours else '')
    if hours: return f'{hours} 小时' + (f' {mins} 分钟' if mins else '')
    return f'{mins} 分钟'

def stamp_message(message):
    """用户消息前加「[YYYY-MM-DD 周X HH:MM] 」；助手消息不加，避免模型在回复里模仿时间标记。"""
    if message.role!='user': return message.content
    dt=_local_time(message.created_at)
    return f'[{format_time(dt)}] {message.content}' if dt else message.content

def time_context(history,now):
    elapsed=''; last=None
    for m in reversed(history or []):
        last=_local_time(m.created_at)
        if last is not None: break
    if last is not None and (now-last).total_seconds()>=600:  # 10 分钟以内视为连续对话，不额外提示
        elapsed=f'距离上一条消息（{format_time(last)}）已经过去约 {format_elapsed((now-last).total_seconds())}。'
    return _TIME_NOTE.format(now=format_time(now),elapsed=elapsed)

class PromptBuilder:
    def __init__(self,characters_root=None): self.characters_root=Path(characters_root) if characters_root else None
    def effective_system_prompt(self,settings,character_id,role_prompt=None):
        if role_prompt and role_prompt.strip(): return role_prompt.strip()
        if self.characters_root:
            prompt=load_character_prompt(self.characters_root,character_id)
            if prompt.strip(): return prompt.strip()
        return settings.default_system_prompt.strip()
    def build_messages(self,settings,character_id,history,user_text,role_prompt=None,now=None):
        """时间感知：历史用户消息前加发送时间标记，system 末尾附当前时间与距上条消息的间隔。
        时间取自已落盘的 ChatMessage.created_at，只在发给模型时附加，不改动会话文件。"""
        now=(now or datetime.now().astimezone()).astimezone()
        system=self.effective_system_prompt(settings,character_id,role_prompt)
        system=(system+'\n\n'+time_context(history,now)).strip()
        trimmed=self.trim_history(history,settings.history_message_limit,settings.history_char_limit)
        return [{'role':'system','content':system},*({'role':m.role,'content':stamp_message(m)} for m in trimmed),{'role':'user','content':user_text.strip()}]
    @staticmethod
    def trim_history(history,message_limit,char_limit):
        result=[]; used=0; limit=max(100,char_limit)
        for m in reversed(history):
            if result and (len(result)>=max(1,message_limit) or used+len(m.content)>limit): break
            if not result and len(m.content)>limit:
                result.append(ChatMessage(m.role,m.content[-limit:],m.created_at,m.message_id)); break
            result.append(m); used+=len(m.content)
        return list(reversed(result))