#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Патчит AndroidManifest.xml от Winlator под наше приложение.
Запускается автоматически в GitHub Actions после копирования исходников Winlator.
"""
import pathlib
import re
import sys

MANIFEST = pathlib.Path("app/src/main/AndroidManifest.xml")

OUR_ACTIVITY = '''
        <activity
            android:name="ai.venice.rbxstudio.MainActivity"
            android:exported="true"
            android:label="PortBox"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize|density|uiMode|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
'''


def main() -> int:
    if not MANIFEST.exists():
        print("!! Не найден " + str(MANIFEST), file=sys.stderr)
        return 1

    text = MANIFEST.read_text(encoding="utf-8")
    original = text

    # 1. убираем package="..." (namespace задан в build.gradle.kts)
    text = re.sub(r'\n?\s*package\s*=\s*"[^"]*"', '', text, count=1)

    # 2. убираем LAUNCHER у родной активности Winlator,
    #    чтобы в меню телефона была одна иконка - наша
    text = re.sub(
        r'<category\s+android:name\s*=\s*"android\.intent\.category\.LAUNCHER"\s*/>',
        '',
        text,
        count=1,
    )

    # 3. название приложения
    text = re.sub(
        r'(<application\b[^>]*?)android:label\s*=\s*"[^"]*"',
        r'\1android:label="PortBox"',
        text,
        count=1,
    )

    # 4. вставляем нашу активность-лаунчер
    if 'ai.venice.rbxstudio.MainActivity' not in text:
        text = text.replace('</application>', OUR_ACTIVITY + '    </application>')

    if text == original:
        print("== Манифест не изменился")
    MANIFEST.write_text(text, encoding="utf-8")
    print("== Манифест пропатчен")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
