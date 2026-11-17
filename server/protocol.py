"""Field names and validation for the recovered v7 wire protocol."""

import hashlib
import json
from datetime import datetime, timezone


COMMON = "uid user_id team_id entropy created_at updated_at"
TABLE_FIELDS = {
    49: "name sequence status",
    59: "name",
    69: "name map_type coor_type longitude latitude country province city district",
    76: "task_id schedule_id start_at begin_time check_at cost_time role score status",
    77: "schedule_id task_id alert_time",
    78: ("schedule_id task_id status repeat_start repeat_end repeat_count repeat_start_day "
         "day_interval repeat_start_week week_interval repeat_start_month month_interval "
         "repeat_start_year year_interval month day week weekday"),
    79: "task_id start_at end_at is_all_day is_repeat",
    83: "task_id child_task_id status",
    84: "task_id tag_name",
    86: "content task_id type",
    87: "name task_id sequence",
    88: "task_id role sequence status",
    89: "name rank privacy project_id position_id weight",
    98: "project_id sequence role status",
    99: "name intro color privacy status",
}
TABLE_FIELDS = {table: set(f"{COMMON} {fields}".split()) for table, fields in TABLE_FIELDS.items()}
OWNER = 7680
MEMBER = 448
FAR_FUTURE = "2099-12-31 23:59:59"


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = str(status)


def number(value, minimum=0, maximum=2**63 - 1):
    if not isinstance(value, (int, str)) or isinstance(value, bool):
        raise ApiError(1, "无效的数字参数")
    try:
        result = int(value)
    except ValueError as exc:
        raise ApiError(1, "无效的数字参数") from exc
    if not minimum <= result <= maximum:
        raise ApiError(1, "数字参数超出范围")
    return result


def nested(params, field, expected, default=None):
    value = params.get(field)
    if value is None and default is not None:
        return default
    try:
        decoded = json.loads(value) if isinstance(value, str) else value
    except ValueError as exc:
        raise ApiError(1, f"{field} 格式错误") from exc
    if not isinstance(decoded, expected):
        raise ApiError(1, f"{field} 格式错误")
    return decoded


def string(value, limit, *, required=False):
    if not isinstance(value, str) or len(value) > limit or (required and not value.strip()):
        raise ApiError(1, "文本参数无效")
    return value


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def timestamp():
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
