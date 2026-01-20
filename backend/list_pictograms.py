
import os

BASE_DIR = 'apps/exercises/pictogram'
TARGET_DIRS = ['strength', 'balance', 'flexibilty', 'aerobic']

for d in TARGET_DIRS:
    path = os.path.join(BASE_DIR, d)
    if os.path.exists(path):
        print(f"--- {d} ---")
        for f in sorted(os.listdir(path)):
            if f.endswith('.jpg') or f.endswith('.png'):
                print(f)
    else:
        print(f"Directory not found: {path}")
