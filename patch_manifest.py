import re
import pathlib

p = pathlib.Path("app/src/main/AndroidManifest.xml")
s = p.read_text()

# убрать package, если он есть (иначе конфликт с namespace)
s = re.sub(r'(<manifest[^>]*?)\s+package="[^"]*"', r'\1', s, count=1)

# снять ярлык запуска с активности Winlator
s = re.sub(
    r'\s*<category\s+android:name="android\.intent\.category\.LAUNCHER"\s*/>',
    '',
    s,
)

# подменить название приложения
if re.search(r'<application[^>]*?android:label="[^"]*"', s):
    s = re.sub(
        r'(<application[^>]*?)android:label="[^"]*"',
        r'\1android:label="PortBox"',
        s,
        count=1,
    )
else:
    s = re.sub(r'<application', r'<application android:label="PortBox"', s, count=1)

our_activity = '''
        <activity
            android:name="ai.venice.rbxstudio.MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|density|uiMode"
            android:screenOrientation="sensorLandscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
'''

s = s.replace("</application>", our_activity + "    </application>")

p.write_text(s)
print("манифест пропатчен")
