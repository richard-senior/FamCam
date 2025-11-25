# Quick Start Guide

## 1. Export Your Model

From the parent directory:
```bash
cd /Users/richard/Personal/imagerec
./export_to_android.sh
```

## 2. Copy Model to App

```bash
# Use float32 for best compatibility
cp best_saved_model/best_float32.tflite app/app/src/main/assets/family_model.tflite

# Or use float16 for smaller size (if GPU available)
# cp best_saved_model/best_float16.tflite app/app/src/main/assets/family_model.tflite
```

## 3. Create Labels File

```bash
cat > app/app/src/main/assets/family_labels.txt << 'EOF'
richard
ragnar
heath
donna
amber
EOF
```

## 4. Update MainActivity

Edit `app/app/src/main/java/com/yolo/detector/MainActivity.kt` line 47-50:

```kotlin
detector = YoloDetector(
    context = this,
    modelPath = "family_model.tflite",    // Changed
    labelPath = "family_labels.txt"       // Changed
)
```

## 5. Open in Android Studio

```bash
# Open Android Studio, then:
# File → Open → Select /Users/richard/Personal/imagerec/app
```

## 6. Run on Device

- Connect Android phone via USB
- Enable Developer Options & USB Debugging on phone
- Click green "Run" button in Android Studio
- Select your device

## Testing Without Your Model

The app includes default COCO labels. To test immediately:

1. Download a pre-trained YOLOv8n model:
```bash
cd app/app/src/main/assets
curl -L https://github.com/ultralytics/assets/releases/download/v0.0.0/yolov8n.tflite -o yolov8n_float32.tflite
```

2. The default labels.txt is already included

3. Run the app!

## Saved Images Location

Images are saved to: `/sdcard/Pictures/YOLODetector/`

You can access them via:
- File manager on phone
- Android Studio → Device File Explorer
- `adb pull /sdcard/Pictures/YOLODetector/`
