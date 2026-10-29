# شغّل في Google Colab بعد رفع مجلد dataset/ (train + valid بصيغة COCO وفيهم labels.json)
# !pip install mediapipe-model-maker
from mediapipe_model_maker import object_detector

train_data = object_detector.Dataset.from_coco_folder("dataset/train", cache_dir="/tmp/od_data/train")
val_data = object_detector.Dataset.from_coco_folder("dataset/valid", cache_dir="/tmp/od_data/valid")

hparams = object_detector.HParams(export_dir="exported_model", epochs=60, batch_size=8)
options = object_detector.ObjectDetectorOptions(
    supported_model=object_detector.SupportedModels.MOBILENET_MULTI_AVG,
    hparams=hparams,
)
model = object_detector.ObjectDetector.create(train_data=train_data, validation_data=val_data, options=options)

loss, coco_metrics = model.evaluate(val_data, batch_size=4)
print("loss:", loss, "metrics:", coco_metrics)

model.export_model()   # الناتج: exported_model/model.tflite
