# تدريب موديل مخصص (ملاكي / ميكروباص / توك توك / نقل / أتوبيس / دراجة نارية)

هتعمله مرة واحدة على Google Colab (مجاني) وبعدين تحط الملف الناتج في التطبيق.

## 1) جمع الصور
- صوّر فيديو من نفس مكان التثبيت وزاوية الكاميرا النهائية (نهار وليل لو هتشتغل ليل).
- اسحب فريم كل 0.5 ثانية تقريباً. الهدف **300 إلى 500 صورة لكل فئة** على الأقل.

## 2) التعليم (Labeling) على Roboflow أو CVAT
أسماء الفئات بالضبط (حروف صغيرة): `car`, `microbus`, `tuktuk`, `truck`, `bus`, `motorcycle`
- Export بصيغة **COCO JSON** وقسّم: train / valid.
- داخل كل مجلد (train و valid) أعد تسمية ملف `_annotations.coco.json` إلى `labels.json`.

## 3) التدريب في Colab
ارفع المجلد `dataset/` (فيه train و valid) ثم شغّل `train_model.py` الموجود في هذا المجلد
(أو انسخ محتواه في خلية Colab).

## 4) الاستخدام
انسخ الناتج `exported_model/model.tflite` إلى `app/src/main/assets/` بالاسم `vehicles_custom.tflite` وأعد البناء.
