# FamCam - Family Recognition Training Data Collector

**Create an AI training set of your family so you can spot them, or people who are not them!**

## What is FamCam?

FamCam is an Android app that automatically collects training images for building a family member recognition AI model. It runs in the background, capturing images when it detects people, cats, or dogs, and saves them with YOLO-format labels embedded in the image metadata.

## Features

- **Automatic Data Collection**: Captures 8 photos per hour (64 over 8 hours)
- **Smart Detection**: Only saves images with people, cats, or dogs detected
- **Background Operation**: Runs as a foreground service with minimal battery impact
- **Embedded Labels**: YOLO annotations stored in EXIF ImageDescription field
- **Configurable**: Adjust capture rate, confidence threshold, and detection settings
- **Privacy-Focused**: All data stays on your device

## Use Cases

- Build a custom YOLO model to recognize family members
- Create a home security system that knows who belongs
- Train AI to distinguish between family and strangers
- Collect diverse training data for face recognition projects

## Technical Details

- **Detection Model**: YOLOv8 Nano (person, cat, dog)
- **Image Format**: 640x640 JPEG with EXIF labels
- **Label Format**: YOLO standard (classId x_center y_center width height)
- **Storage**: `/sdcard/DCIM/FamCam/images/`
- **Power Optimization**: 3 FPS processing, camera preview disabled when backgrounded

## Building

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 22) ./gradlew installDebug
```

## Extracting Training Data

```bash
# Pull images from device
adb pull /sdcard/DCIM/FamCam/images/ ./training_data/

# Extract labels from EXIF
python3 ../extract_labels_from_exif.py training_data training_data/annotations
```

## License

Personal use project for family AI training.
