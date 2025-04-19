import cv2
import numpy as np
import mediapipe as mp
from ultralytics import YOLO
import math

class TongueDetector:
    def __init__(self, model_path):
        self.face_mesh = mp.solutions.face_mesh.FaceMesh(
            max_num_faces=1, 
            refine_landmarks=True,
            min_detection_confidence=0.5,
            min_tracking_confidence=0.5
        )
        self.model = YOLO(model_path)
        self.mouth_indices = [61, 291, 0, 17]
        self.prev_box = None
        self.smoothing_factor = 0.6
        
    def smooth_box(self, current_box):
        if self.prev_box is None:
            self.prev_box = current_box
            return current_box
            
        smoothed_box = [
            int(self.prev_box[0] * self.smoothing_factor + current_box[0] * (1 - self.smoothing_factor)),
            int(self.prev_box[1] * self.smoothing_factor + current_box[1] * (1 - self.smoothing_factor)),
            int(self.prev_box[2] * self.smoothing_factor + current_box[2] * (1 - self.smoothing_factor)),
            int(self.prev_box[3] * self.smoothing_factor + current_box[3] * (1 - self.smoothing_factor))
        ]
        self.prev_box = smoothed_box
        return smoothed_box
    
    def get_tongue_direction(self, mouth_center, tongue_box):
        if tongue_box is None:
            return "Tongue not detected"
        
        x_min, y_min, x_max, y_max = tongue_box
        tongue_center = ((x_min + x_max) / 2, (y_min + y_max) / 2)
        mouth_x, mouth_y = mouth_center
        
        dx = tongue_center[0] - mouth_x
        dy = tongue_center[1] - mouth_y
        
        length = math.sqrt(dx**2 + dy**2)
        if length < 10:
            return "center"
        
        angle = math.degrees(math.atan2(dy, dx))
        
        if -45 <= angle < 45:
            return "right"
        elif 45 <= angle < 135:
            return "down"
        elif angle >= 135 or angle < -135:
            return "left"
        else:
            return "up"
    
    def process_frame(self, frame):
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        h, w = frame.shape[:2]
        
        results = self.face_mesh.process(rgb_frame)
        tongue_box = None
        direction = "Tongue not detected"
        mouth_center = None
        tongue_center = None
        
        if results.multi_face_landmarks:
            landmarks = results.multi_face_landmarks[0].landmark
            mouth_points = np.array([(int(landmarks[i].x * w), 
                                    int(landmarks[i].y * h)) 
                                   for i in self.mouth_indices])
            
            min_x, min_y = np.min(mouth_points, axis=0)
            max_x, max_y = np.max(mouth_points, axis=0)
            mouth_center = (int((min_x + max_x) / 2), int((min_y + max_y) / 2))
            
            cv2.circle(frame, mouth_center, 3, (255, 0, 0), -1)
            
            yolo_results = self.model(frame, verbose=False)[0]
            if len(yolo_results.boxes) > 0:
                raw_box = yolo_results.boxes.xyxy[0].cpu().numpy().astype(int)
                tongue_box = self.smooth_box(raw_box)
                
                cv2.rectangle(frame, 
                            (tongue_box[0], tongue_box[1]), 
                            (tongue_box[2], tongue_box[3]), 
                            (0, 0, 255), 2)

                tongue_center = (int((tongue_box[0] + tongue_box[2]) / 2), 
                               int((tongue_box[1] + tongue_box[3]) / 2))
                cv2.circle(frame, tongue_center, 3, (255, 255, 0), -1)
                
                # Рисуем линию между центром рта и центром языка
                if mouth_center and tongue_center:
                    cv2.line(frame, mouth_center, tongue_center, (0, 255, 255), 2)
                
                direction = self.get_tongue_direction(mouth_center, tongue_box)
        
        cv2.putText(frame, f"Direction: {direction}", (10, 30), 
                   cv2.FONT_HERSHEY_SIMPLEX, 0.7, 
                   (0, 255, 0) if "not detected" not in direction else (0, 0, 255), 2)
        
        return frame

def main():
    detector = TongueDetector(
        model_path="/Users/igorzolotyh/SafeVision/FaceSpeech/runs/train/exp8/weights/best.pt"
    )
    
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        print("Error: Could not open webcam")
        return
    
    while True:
        ret, frame = cap.read()
        if not ret:
            print("Frame error")
            break
        
        processed_frame = detector.process_frame(frame)
        cv2.imshow("Tongue Detection", processed_frame)
        
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break
    
    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()