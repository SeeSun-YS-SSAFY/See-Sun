"""
주간·월간 식단 리포트 (2차 의뢰서 §5-3, 02 문서 DRPT-03·04)

- 주간: 월요일 시작 7일, 날짜별 점수 + 평균 + 피드백(권장 범위 안 날 수, 자주 부족·초과 영양소, 지난주 대비)
- 월간: 그 달의 주차(월요일 시작)별 평균
- 오늘 이후 날짜는 '기록 없음'으로 세지 않는다. 지난 날 중 3일 이상 기록이 없으면 '분석 신뢰도 낮음'
"""
from datetime import date, timedelta

from apps.health.services import get_health_context
from apps.users.profile import local_today
from seesun_rules import get_tables
from seesun_rules.bands import round_half_up
from seesun_rules.diet_score import grade_for

from .services import daily_report


def week_start(day: date) -> date:
    return day - timedelta(days=day.weekday())


def _days(user, start, end, ctx):
    today = local_today()
    out, day = [], start
    while day <= end:
        if day > today:
            out.append({'date': day, 'report': None, 'future': True})
        else:
            out.append({'date': day, 'report': daily_report(user, day, ctx), 'future': False})
        day += timedelta(days=1)
    return out


def _average(days):
    totals = [d['report']['total'] for d in days if d['report'] and d['report']['total'] is not None]
    return round_half_up(sum(totals) / len(totals), 1) if totals else None


def _feedback(days, tables):
    scored = [d['report'] for d in days if d['report'] and d['report']['status'] == 'OK']
    labels = tables.DIET_SCORE['macro']['labels']
    in_range = sum(1 for r in scored if all(v == 'OK' for v in r['components']['macro']['status'].values()))
    threshold = tables.REPORT['frequent_ratio'] * len(scored)
    low, high = [], []
    for key in labels:
        lows = sum(1 for r in scored if r['components']['macro']['status'][key] == 'LOW')
        highs = sum(1 for r in scored if r['components']['macro']['status'][key] == 'HIGH')
        if scored and lows >= threshold and lows:
            low.append(key)
        if scored and highs >= threshold and highs:
            high.append(key)
    return {'days_macro_in_range': in_range, 'frequent_low': low, 'frequent_high': high,
            'frequent_low_labels': [labels[k] for k in low], 'frequent_high_labels': [labels[k] for k in high]}


def _summary(average, tables):
    if average is None:
        return None, '기록 부족', '기록이 없어요.'
    code, label, phrase = grade_for(round_half_up(average), tables)
    return code, label, phrase


def weekly(user, start: date):
    tables = get_tables()
    ctx = get_health_context(user)
    start = week_start(start)
    end = start + timedelta(days=6)
    days = _days(user, start, end, ctx)
    prev_days = _days(user, start - timedelta(days=7), start - timedelta(days=1), ctx)
    average, prev_average = _average(days), _average(prev_days)
    grade, label, phrase = _summary(average, tables)
    names = tables.REPORT['weekday_names']
    past = [d for d in days if not d['future']]
    missing = sum(1 for d in past if d['report']['status'] == 'NO_RECORD')
    reliability = 'LOW' if missing >= tables.REPORT['week_low_reliability_missing_days'] else 'OK'
    feedback = _feedback(days, tables)
    change = None if average is None or prev_average is None else round_half_up(average - prev_average, 1)
    feedback['change_vs_previous'] = change

    day_rows, list_tts = [], []
    for d in days:
        r = d['report']
        total = r['total_display'] if r else None
        day_rows.append({'date': d['date'].isoformat(), 'weekday': names[d['date'].weekday()],
                         'total': total, 'grade': r['grade'] if r else None,
                         'recorded': bool(r and r['status'] != 'NO_RECORD')})
        if not d['future']:
            list_tts.append(f"{names[d['date'].weekday()]} {total}점" if total is not None
                            else f"{names[d['date'].weekday()]} 기록 없음")

    tts = (f'주간 평균 {round_half_up(average)}점, {label}입니다. ' if average is not None else '이번 주 기록이 없어요. ')
    if feedback['frequent_low_labels']:
        tts += f"{', '.join(feedback['frequent_low_labels'])}이 자주 부족했어요. "
    if feedback['frequent_high_labels']:
        tts += f"{', '.join(feedback['frequent_high_labels'])}이 자주 많았어요. "
    if change is not None:
        tts += f"지난주보다 {abs(change):g}점 {'올랐어요' if change >= 0 else '내려갔어요'}. "
    if reliability == 'LOW':
        tts += '기록한 날이 적어 분석 신뢰도가 낮아요.'
    return {
        'period': 'week', 'from': start.isoformat(), 'to': end.isoformat(),
        'average': average, 'grade': grade, 'grade_label': label, 'one_liner': phrase,
        'days': day_rows, 'feedback': feedback, 'reliability': reliability,
        'list_tts': list_tts, 'tts_message': tts.strip(),
    }


def monthly(user, any_day: date):
    tables = get_tables()
    ctx = get_health_context(user)
    first = any_day.replace(day=1)
    last = (first.replace(year=first.year + 1, month=1) if first.month == 12
            else first.replace(month=first.month + 1)) - timedelta(days=1)
    days = _days(user, first, last, ctx)
    average = _average(days)
    grade, label, phrase = _summary(average, tables)
    weeks, cursor, index = [], week_start(first), 1
    while cursor <= last:
        in_month = [d for d in days if cursor <= d['date'] <= cursor + timedelta(days=6)]
        weeks.append({'week': index, 'week_start': cursor.isoformat(), 'average': _average(in_month),
                      'recorded_days': sum(1 for d in in_month if d['report'] and d['report']['status'] != 'NO_RECORD')})
        cursor += timedelta(days=7)
        index += 1
    past = [d for d in days if not d['future']]
    recorded = sum(1 for d in past if d['report']['status'] != 'NO_RECORD')
    list_tts = [f"{w['week']}주차 {round_half_up(w['average'])}점" if w['average'] is not None else f"{w['week']}주차 기록 없음"
                for w in weeks]
    tts = (f'{first.month}월 평균 {round_half_up(average)}점, {label}입니다.' if average is not None
           else f'{first.month}월 기록이 없어요.')
    return {
        'period': 'month', 'from': first.isoformat(), 'to': last.isoformat(),
        'average': average, 'grade': grade, 'grade_label': label, 'one_liner': phrase,
        'weeks': weeks, 'feedback': _feedback(days, tables), 'recorded_days': recorded,
        'reliability': 'LOW' if past and recorded < len(past) / 2 else 'OK',
        'list_tts': list_tts, 'tts_message': tts,
    }
