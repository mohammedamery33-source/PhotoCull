# فرز الصور (PhotoCull)

تطبيق أندرويد (Kotlin + Jetpack Compose + ML Kit) لفرز صور المصورين: بيكشف الصور المهزوزة، العين المقفولة،
الإضاءة الغلط، واللقطات المكررة (Burst) مع اختيار أفضل لقطة. الصور المرفوضة بتتنقل لفولدر `_Rejected` (مفيش حذف نهائي).

## بناء الـAPK (من غير Android Studio)
1. اعمل Repository على GitHub وارفع محتوى المشروع.
2. افتح تاب Actions ← Build APK ← Run workflow (بيشتغل لوحده مع أول push).
3. بعد ~5 دقايق نزّل الـAPK من Artifacts.

## بناء الـAPK من Android Studio
افتح الفولدر، وسيبه يعمل Sync (Gradle 8.7 / JDK 17)، وبعدين Build ← Build APK(s).
