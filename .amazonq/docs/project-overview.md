# FamCam - Project Overview

## Purpose
Android camera app for automated family photo collection and YOLO object detection training data generation.

## Core Functionality

### 1. Automated Photo Capture
- Runs YOLOv8 Nano object detection on camera feed at configurable FPS (default: 3 FPS)
- Auto-saves images when person/cat/dog detected at configurable intervals
- Saves to `DCIM/FamCam/images/` as 640x640 JPEG
- Stores bounding box data in EXIF `TAG_IMAGE_DESCRIPTION` field (YOLO format: `classId cx cy w h`)

### 2. Power Mode
- Keeps screen on with minimum brightness (0.0f)
- Applies 80% dark overlay to camera preview
- Enables continuous 24/7 operation for data collection
- Toggle via ⚡ button

### 3. Image Labeling Interface
- Browse all captured images via MediaStore
- Display bounding boxes from EXIF data
- Touch box → assign class from configurable list
- Delete images with system permission dialog
- Navigate: ◀ Previous, ▶ Next, 🗑️ Delete, ✕ Close

### 4. Dataset Export
- Exports YOLO-format training dataset as ZIP
- Structure: `images/`, `labels/`, `coco.yaml`
- Output: `/sdcard/Download/FamCamData.zip`
- Trigger via 📦 button

## Technical Architecture

### Detection Pipeline
```
Camera (CameraX) → ImageAnalysis (3 FPS) → YOLOv8 Nano (640x640) → 
Filter (person/cat/dog) → Cache → Auto-save (interval-based) → 
EXIF metadata → MediaStore
```

### Key Classes
- **MainActivity**: Camera, detection, auto-save, power mode
- **LabelingActivity**: Image browser, annotation, export
- **YoloDetector**: TFLite inference wrapper
- **LabelOverlayView**: Interactive bounding box display with fitCenter scaling
- **SettingsActivity**: Configuration (intervals, confidence, classes, FPS)

### Models
- **yolov8n_float32.tflite**: 12.7 MB, detects 80 COCO classes
- Input: 640x640 RGB
- Output: Bounding boxes with class IDs and confidence

### Storage & Permissions
- **Android 13+ Scoped Storage**: Uses MediaStore APIs exclusively
- **Permissions**: READ_MEDIA_IMAGES, CAMERA, POST_NOTIFICATIONS
- **Write Operations**: Requires user confirmation via `createWriteRequest()`
- **Delete Operations**: Requires user confirmation via `createDeleteRequest()`

## Configuration (Settings)

| Setting | Default | Purpose |
|---------|---------|---------|
| Max Images | 64 | Images per period |
| Period Hours | 8 | Time window |
| Confidence | 70% | Detection threshold |
| Frame Rate | 3 FPS | Processing rate |
| Save Path | FamCam | DCIM subdirectory |
| Classes | person,cat,dog,richard,donna,ragnar,heath,amber,lena | Annotation labels |

**Auto-save Interval**: `(Period Hours × 3600 × 1000) / Max Images` ms
- Default: 450 seconds (7.5 minutes)
- Debug mode: 10 seconds

## Data Format

### EXIF Storage (TAG_IMAGE_DESCRIPTION)
```
classId cx cy w h
classId cx cy w h
...
```
- Coordinates: Normalized 0-1 (YOLO format)
- Example: `0 0.481 0.538 0.959 0.920`

### Exported Labels (.txt)
Same format as EXIF, one file per image.

### coco.yaml
```yaml
names:
  0: person
  1: cat
  2: dog
  ...
nc: 9
train: images
val: images
```

## UI Layout

### Main Screen
- Camera preview with detection overlay
- Bottom bar: ⚡ Power | 🏷️ Label | ⚙️ Settings | ✕ Quit
- Dark theme with #404040 buttons on #000000 background

### Labeling Screen
- Image display with bounding box overlay
- Bottom bar: ◀ Prev | ▶ Next | 📦 Export | 🗑️ Delete | ✕ Back
- Touch box → class selection dialog
- Overlay uses fitCenter scaling with proper offset calculation

## Build Configuration
- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Gradle**: 8.12
- **Dependencies**: CameraX, TensorFlow Lite, ExifInterface

## Common Issues & Solutions

### Bounding Box Misalignment
- Overlay must account for ImageView fitCenter scaling
- Calculate: `scale = min(viewWidth/640, viewHeight/640)`
- Apply offset: `(viewSize - displayedSize) / 2`

### Scoped Storage Access
- Never use `File.exists()` or `File.delete()` directly
- Always query MediaStore first, get URI, use ContentResolver
- Write/delete requires user permission dialogs on Android 11+

### Camera Stops When Backgrounded
- Android pauses camera callbacks when app not visible
- Power mode keeps screen on to maintain foreground state
- No true background operation possible with CameraX

## Development Workflow
1. Modify code in Android Studio
2. Build: `./gradlew assembleDebug`
3. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Logs: `adb logcat -s YOLO:* LabelingActivity:*`

## Future Enhancements
- Custom family member detection model
- Transfer learning from base YOLOv8
- IMX500 edge deployment
- Periodic wake-up for true background operation
