# MyStore

חנות אפליקציות פנימית: סורקת תיקייה בקושחה, מציגה רשת של סמלים עם שמות,
ובלחיצה על סמל מתקינה את ה-APK בהתקנה שקטה (`PackageInstaller.Session`).

## התקנה אוטומטית
להתקנה עם root בלחיצות יש פרויקט נפרד: **MyStoreInstaller** ("מתקין החנות"). בוחרים בו את קובץ ה-APK של החנות
(`MyStore-debug` מה-Artifacts) והוא מתקין אותו כ-priv-app עם כל הקבצים וההרשאות.

## התקנה ידנית על המכשיר (root)
האפליקציה צריכה להיות priv-app, אחרת ההתקנה השקטה לא תעבוד.

```sh
adb root && adb remount          # או כל דרך אחרת שלך לכתוב ל-/system
adb shell mkdir -p /system/priv-app/MyStore /system/etc/mystore/apks
adb push MyStore.apk /system/priv-app/MyStore/MyStore.apk
adb push privapp/privapp-permissions-mystore.xml /system/etc/permissions/
adb push my_app1.apk /system/etc/mystore/apks/
adb shell chmod 755 /system/priv-app/MyStore /system/etc/mystore /system/etc/mystore/apks
adb shell chmod 644 /system/priv-app/MyStore/MyStore.apk \
    /system/etc/permissions/privapp-permissions-mystore.xml \
    /system/etc/mystore/apks/*.apk
adb reboot
```

## תכונות
- רשת אפליקציות מעוצבת (סמל + שם + סטטוס), משיכה כלפי מטה מרעננת את הרשימה.
- התראה כשההתקנה הצליחה (לחיצה עליה פותחת את האפליקציה). בהפעלה הראשונה האפליקציה מבקשת הרשאת התראות (אנדרואיד 13+); בלי ההרשאה תוצג הודעה קצרה במקום.
- אישור לפני הסרה.
- הגדרות (אייקון גלגל שיניים למעלה), בכרטיסיות: **תצוגה** (אוטומטי / בהיר / כהה + ערכת נושא), **נעילה** (קוד 4-8 ספרות), **אודות**.
- כשמוגדר קוד, תוכן החנות מוסתר לגמרי עד להזנת הקוד (גם בחזרה מרקע, וגם בתצוגה המקדימה של האפליקציות האחרונות).
- אייקון לאפליקציה.

## התנהגות
- התיקייה נקבעת ב-`Config.kt` (`APK_DIRS`).
- הסמל והשם נקראים מתוך ה-APK עצמו, אין קובץ קטלוג.
- לחיצה: התקנה / עדכון (אם גרסה בתיקייה חדשה יותר), ואם כבר מותקן - פתיחה.
- לחיצה ארוכה: הסרה.
- עדכון מצליח רק אם ה-APK חתום באותה חתימה כמו הגרסה המותקנת.

## בנייה
GitHub Actions בונה בכל push (Artifacts -> MyStore-debug), או מקומית: `gradle assembleDebug`.
