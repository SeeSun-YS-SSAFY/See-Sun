"""
백엔드 운동 픽스처(29종) → Android 기기 내장 운동 정보(오프라인 대체용) JSON.

서버에 닿지 못할 때(비행기 모드·로그인 전) 앱이 운동 목록과 대본을 보여 주고 기기 음성(TTS)으로 읽도록 쓴다.
운동 ID는 서버 픽스처와 같아서, 오프라인에서 한 운동 기록도 나중에 그대로 서버에 올라간다.
픽스처를 고치면 다시 실행한다.

    backend\.venv\Scripts\python.exe scripts\demo\export_exercise_catalog.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / 'backend' / 'apps' / 'exercises' / 'fixtures' / 'exercise_data.json'
OUT = ROOT / 'android' / 'core' / 'data' / 'src' / 'main' / 'assets' / 'exercise_catalog.json'
FIELDS = ('exercise_description', 'first_description', 'main_form', 'form_description', 'stay_form', 'fixed_form',
          'exercise_guide_text')


def main():
    rows = json.loads(FIXTURE.read_text(encoding='utf-8'))
    categories = [{'category_id': r['pk'], 'display_name': r['fields']['display_name']}
                  for r in rows if r['model'] == 'exercises.exercisecategory']
    exercises = []
    for r in rows:
        if r['model'] != 'exercises.exercise' or not r['fields'].get('is_active', True):
            continue
        f = r['fields']
        item = {'exercise_id': r['pk'], 'category_id': f['category'], 'exercise_name': f['exercise_name']}
        item.update({k: f.get(k) for k in FIELDS if f.get(k)})
        exercises.append(item)
    OUT.write_text(json.dumps({'source': 'backend/apps/exercises/fixtures/exercise_data.json',
                               'categories': categories, 'exercises': exercises},
                              ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    print(f'{OUT} — 카테고리 {len(categories)}개, 운동 {len(exercises)}개')


if __name__ == '__main__':
    main()
