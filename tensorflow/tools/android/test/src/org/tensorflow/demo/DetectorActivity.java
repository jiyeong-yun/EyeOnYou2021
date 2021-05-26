/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.demo;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Vector;
import org.tensorflow.demo.OverlayView.DrawCallback;
import org.tensorflow.demo.env.BorderedText;
import org.tensorflow.demo.env.ImageUtils;
import org.tensorflow.demo.env.Logger;
import org.tensorflow.demo.tracking.MultiBoxTracker;
import org.tensorflow.demo.R; // Explicit import needed for internal Google builds.
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {


  public TextToSpeech tts;

  private static final Logger LOGGER = new Logger();

  // Configuration values for the prepackaged multibox model.
  private static final int MB_INPUT_SIZE = 224;
  private static final int MB_IMAGE_MEAN = 128;
  private static final float MB_IMAGE_STD = 128;
  private static final String MB_INPUT_NAME = "ResizeBilinear";
  private static final String MB_OUTPUT_LOCATIONS_NAME = "output_locations/Reshape";
  private static final String MB_OUTPUT_SCORES_NAME = "output_scores/Reshape";
  private static final String MB_MODEL_FILE = "file:///android_asset/multibox_model.pb";
  private static final String MB_LOCATION_FILE =
      "file:///android_asset/multibox_location_priors.txt";

  private static final int TF_OD_API_INPUT_SIZE = 300;
  private static final String TF_OD_API_MODEL_FILE =
      "file:///android_asset/ssd_mobilenet_v1_android_export.pb";
  private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/coco_labels_list.txt";


  // Configuration values for tiny-yolo-voc. Note that the graph is not included with TensorFlow and
  // must be manually placed in the assets/ directory by the user.
  // Graphs and models downloaded from http://pjreddie.com/darknet/yolo/ may be converted e.g. via
  // DarkFlow (https://github.com/thtrieu/darkflow). Sample command:
  // ./flow --model cfg/tiny-yolo-voc.cfg --load bin/tiny-yolo-voc.weights --savepb --verbalise

//  private static final String YOLO_MODEL_FILE = "file:///android_asset/graph-tiny-yolo-voc.pb";
  private static final String YOLO_MODEL_FILE = "file:///android_asset/my-tiny-yolo.pb";
  private static final int YOLO_INPUT_SIZE = 416;
  private static final String YOLO_INPUT_NAME = "input";
  private static final String YOLO_OUTPUT_NAMES = "output";
  private static final int YOLO_BLOCK_SIZE = 32;

  // Which detection model to use: by default uses Tensorflow Object Detection API frozen
  // checkpoints.  Optionally use legacy Multibox (trained using an older version of the API)
  // or YOLO.
  private enum DetectorMode {
    TF_OD_API, MULTIBOX, YOLO;
  }

//  private static final DetectorMode MODE = DetectorMode.TF_OD_API;
  private static final DetectorMode MODE = DetectorMode.YOLO;

  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.6f;
  private static final float MINIMUM_CONFIDENCE_MULTIBOX = 0.1f;
  private static final float MINIMUM_CONFIDENCE_YOLO = 0.50f; // 기본은 0.25f

  private static final boolean MAINTAIN_ASPECT = MODE == DetectorMode.YOLO;

  private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);

  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;

  private Integer sensorOrientation;

  private Classifier detector;

  private long lastProcessingTimeMs;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private boolean computingDetection = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private byte[] luminanceCopy;

  private BorderedText borderedText;
  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    int cropSize = TF_OD_API_INPUT_SIZE;
    if (MODE == DetectorMode.YOLO) {
      detector =
          TensorFlowYoloDetector.create(
              getAssets(),
              YOLO_MODEL_FILE,
              YOLO_INPUT_SIZE,
              YOLO_INPUT_NAME,
              YOLO_OUTPUT_NAMES,
              YOLO_BLOCK_SIZE);
      cropSize = YOLO_INPUT_SIZE;
    } else if (MODE == DetectorMode.MULTIBOX) {
      detector =
          TensorFlowMultiBoxDetector.create(
              getAssets(),
              MB_MODEL_FILE,
              MB_LOCATION_FILE,
              MB_IMAGE_MEAN,
              MB_IMAGE_STD,
              MB_INPUT_NAME,
              MB_OUTPUT_LOCATIONS_NAME,
              MB_OUTPUT_SCORES_NAME);
      cropSize = MB_INPUT_SIZE;
    } else {
      try {
        detector = TensorFlowObjectDetectionAPIModel.create(
            getAssets(), TF_OD_API_MODEL_FILE, TF_OD_API_LABELS_FILE, TF_OD_API_INPUT_SIZE);
        cropSize = TF_OD_API_INPUT_SIZE;
      } catch (final IOException e) {
        LOGGER.e(e, "Exception initializing classifier!");
        Toast toast =
            Toast.makeText(
                getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
        toast.show();
        finish();
      }
    }

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    sensorOrientation = rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    frameToCropTransform =
        ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
            sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

    trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
        new DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            tracker.draw(canvas);
            if (isDebug()) {
              tracker.drawDebug(canvas);
            }
          }
        });

    addCallback(
        new DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            if (!isDebug()) {
              return;
            }
            final Bitmap copy = cropCopyBitmap;
            if (copy == null) {
              return;
            }

            final int backgroundColor = Color.argb(100, 0, 0, 0);
            canvas.drawColor(backgroundColor);

            final Matrix matrix = new Matrix();
            final float scaleFactor = 2;
            matrix.postScale(scaleFactor, scaleFactor);
            matrix.postTranslate(
                canvas.getWidth() - copy.getWidth() * scaleFactor,
                canvas.getHeight() - copy.getHeight() * scaleFactor);
            canvas.drawBitmap(copy, matrix, new Paint());

            final Vector<String> lines = new Vector<String>();
            if (detector != null) {
              final String statString = detector.getStatString();
              final String[] statLines = statString.split("\n");
              for (final String line : statLines) {
                lines.add(line);
              }
            }
            lines.add("");

            lines.add("Frame: " + previewWidth + "x" + previewHeight);
            lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
            lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
            lines.add("Rotation: " + sensorOrientation);
            lines.add("Inference time: " + lastProcessingTimeMs + "ms");

            borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
          }
        });
  }

  OverlayView trackingOverlay;

  @Override
  protected void processImage() {
    ++timestamp;
    final long currTimestamp = timestamp;
    byte[] originalLuminance = getLuminance();
    tracker.onFrame(
        previewWidth,
        previewHeight,
        getLuminanceStride(),
        sensorOrientation,
        originalLuminance,
        timestamp);
    trackingOverlay.postInvalidate();

    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    if (luminanceCopy == null) {
      luminanceCopy = new byte[originalLuminance.length];
    }
    System.arraycopy(originalLuminance, 0, luminanceCopy, 0, originalLuminance.length);
    readyForNextImage();

    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    runInBackground(
        new Runnable() {
          @Override
          public void run() {
            LOGGER.i("Running detection on image " + currTimestamp);
            final long startTime = SystemClock.uptimeMillis();
            final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
            final Canvas canvas = new Canvas(cropCopyBitmap);
            final Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Style.STROKE);
            paint.setStrokeWidth(2.0f);

            float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
            switch (MODE) {
              case TF_OD_API:
                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                break;
              case MULTIBOX:
                minimumConfidence = MINIMUM_CONFIDENCE_MULTIBOX;
                break;
              case YOLO:
                minimumConfidence = MINIMUM_CONFIDENCE_YOLO;
                break;
            }

            final List<Classifier.Recognition> mappedRecognitions =
                new LinkedList<Classifier.Recognition>();

            for (final Classifier.Recognition result : results) {
              final RectF location = result.getLocation();
              if (location != null && result.getConfidence() >= minimumConfidence) {
                canvas.drawRect(location, paint);

                cropToFrameTransform.mapRect(location);
                result.setLocation(location);
                mappedRecognitions.add(result);

                // ###: DetectorActivity: [[4088] check_pattern (70.9%) RectF(80.00001, 79.77927, 428.08572, 420.97244)]
                Log.d("###", "DetectorActivity: " + mappedRecognitions);
                // ###: getTitle: check_pattern, result.getTitle()로는 모두 가져오기 가능
                Log.d("###", "getTitle: " + mappedRecognitions.get(0).getTitle());

              }

              //안드로이드 에서는 UI Thread 외부에서 UI 관련 작업을 호출 하면 Exception이 발생한다.
              //android.view.ViewRoot$CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views.
              //이와 같은 경우에는 Activity의 runOnUiThread 를 이용하여 해당 작업을 UI Thread 를 호출해 작업하면 문제를 회피할 수 있다.


              new Thread(new Runnable() {
                @Override
                public void run() {
                  runOnUiThread(new Runnable(){
                    @Override
                    public void run() {
                      // 해당 작업을 처리함
                      Button btn_det = findViewById(R.id.btn_detect);
                      //공유변수 선언(날씨로 옷정보 전송하기)
                      SharedPreferences userinfo = getSharedPreferences("userinfo", MODE_PRIVATE);
                      userinfo.getString("clothes_name", null);

                      tts = new TextToSpeech(DetectorActivity.this, new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int status) {
                          if(status == TextToSpeech.SUCCESS) {
                            // 언어를 선택한다.
                            int result = tts.setLanguage(Locale.KOREA);
                            //if (result==TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
                            // {
                            //     Toast.makeText(activity_weather.this, "인식 버튼 클릭", Toast.LENGTH_SHORT).show();
                            // }
                          }
                        }
                      });
                      btn_det.setOnClickListener(new View.OnClickListener() {
                        TextView labelTextView = findViewById(R.id.labelTextView);
//                        TextView labelTextView2 = findViewById(R.id.labelTextView2);
                        String label, name, name2;
                        int index;

                        @Override
                        public void onClick(View view) {



                          //TODO: label만 추출하기 ==> mappedRecognitions.get(0).getTitle()로 해결 (result.getTitle()로는 모두 가져오기 가능)
                          if(mappedRecognitions.size() != 0) {

                            //activity_weather에서  label을 사용하기 위한 공유 변수
                            name = mappedRecognitions.get(0).getTitle();

                            /*if(mappedRecognitions.size() > 1) {
                              name2 = mappedRecognitions.get(1).getTitle();
                            }*/

                            SharedPreferences userinfo= getSharedPreferences("userinfo", MODE_PRIVATE);
                            SharedPreferences.Editor editor= userinfo.edit();
                            editor.putString("username", name);
                            editor.commit();

                            switch (name) {
                              case "check_pattern":
                                label = "check_pattern";
                                index = 1;
                                labelTextView.setText("체크 패턴의 옷입니다.\n");
                                break;
                              case "dot_pattern":
                                label = "dot_pattern";
                                index = 2;
                                labelTextView.setText("물방울 패턴의 옷입니다.\n");
                                break;
                              case "horizontal_striped":
                                label = "horizontal_striped";
                                index = 3;
                                labelTextView.setText("가로 줄무늬 모양의 옷입니다.\n");
                                break;
                              case "vertical_striped":
                                label = "vertical_striped";
                                index = 4;
                                labelTextView.setText("세로 줄무늬 모양의 옷입니다.\n");
                                break;
                              case "leopard":
                                label = "leopard";
                                index = 5;
                                labelTextView.setText("호피무늬의 옷입니다.\n");
                                break;
                              case "black":
                                label = "black";
                                index = 6;
                                labelTextView.setText("검정색 옷입니다.\n");
                                break;
                              case "gray":
                                label = "gray";
                                index = 7;
                                labelTextView.setText("회색 옷입니다.\n");
                                break;
                              case "blue":
                                label = "blue";
                                index = 8;
                                labelTextView.setText("파란색 옷입니다.\n");
                                break;
                              case "beige":
                                label = "beige";
                                index = 9;
                                labelTextView.setText("베이지색 옷입니다.\n");
                                break;
                              default:
                                labelTextView.setText("위치를 다시 잡아주세요.\n");
                                break;
                            }
                          }else{
                            labelTextView.setText("위치를 다시 잡아주세요.\n");
                          }

/*                          if(name2 != null) {
                            SharedPreferences userinfo2= getSharedPreferences("userinfo2", MODE_PRIVATE);
                            SharedPreferences.Editor editor= userinfo2.edit();
                            editor.putString("username2", name2);
                            editor.commit();

                            switch (name2) {
                              case "check_pattern":
                                label = "check_pattern";
                                index = 1;
                                labelTextView2.setText("체크 패턴의 옷입니다.");
                                break;
                              case "dot_pattern":
                                label = "dot_pattern";
                                index = 2;
                                labelTextView2.setText("물방울 패턴의 옷입니다.");
                                break;
                              case "horizontal_striped":
                                label = "horizontal_striped";
                                index = 3;
                                labelTextView2.setText("가로 줄무늬 모양의 옷입니다.");
                                break;
                              case "vertical_striped":
                                label = "vertical_striped";
                                index = 4;
                                labelTextView2.setText("세로 줄무늬 모양의 옷입니다.");
                                break;
                              case "leopard":
                                label = "leopard";
                                index = 5;
                                labelTextView2.setText("호피무늬의 옷입니다.");
                                break;
                              case "black":
                                label = "black";
                                index = 6;
                                labelTextView2.setText("검정색 옷입니다.");
                                break;
                              case "gray":
                                label = "gray";
                                index = 7;
                                labelTextView2.setText("회색 옷입니다.");
                                break;
                              case "blue":
                                label = "blue";
                                index = 8;
                                labelTextView2.setText("파란색 옷입니다.");
                                break;
                              case "beige":
                                label = "beige";
                                index = 9;
                                labelTextView2.setText("베이지색 옷입니다.");
                                break;
                              default:
                                labelTextView2.setText("위치를 다시 잡아주세요.");
                                break;
                            }
                          }else{
                            labelTextView2.setText("위치를 다시 잡아주세요.");
                          }*/


                          tts.setPitch(1.0f);         // 음성 톤을 2.0배 올려준다.
                          tts.setSpeechRate(1.0f);    // 읽는 속도는 기본 설정
                          tts.speak(labelTextView.getText().toString(), TextToSpeech.QUEUE_FLUSH, null);
//                          tts.speak(labelTextView2.getText().toString(), TextToSpeech.QUEUE_FLUSH, null);

                        }
                      });
                    }
                  });
                }
              }).start();

            }

            tracker.trackResults(mappedRecognitions, luminanceCopy, currTimestamp);
            trackingOverlay.postInvalidate();

            requestRender();
            computingDetection = false;
          }
        });
  }

  @Override
  protected int getLayoutId() {
    return R.layout.camera_connection_fragment_tracking;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  @Override
  public void onSetDebug(final boolean debug) {
    detector.enableStatLogging(debug);
  }
}
