package org.tensorflow.demo;

import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.MotionEvent;
import android.widget.Toast;

import java.util.Locale;

public class InfoActivity extends Activity {
    public TextToSpeech tts;
    String speechtext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);

        //TODO: 실행하자마자 음성 출력
        tts = new TextToSpeech(InfoActivity.this, new TextToSpeech.OnInitListener() {
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

    }

    private boolean waitDouble = true;
    private static final int DOUBLE_CLICK_TIME = 200; // double click timer


    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (waitDouble == true) {
                    waitDouble = false;
                    Thread thread = new Thread() {
                        @Override
                        public void run() {
                            try {
                                sleep(DOUBLE_CLICK_TIME);
                                if (waitDouble == false) {
                                    waitDouble = true;
                                    //single click event
                                    tts.setPitch(1.0f);         // 음성 톤을 1.0배 올려준다.
                                    tts.setSpeechRate(1.0f);    // 읽는 속도는 기본 설정
                                    speechtext="이 어플리케이션은 카메라를 통해 보여진 옷의 무늬 또는 색을 알려줍니다. " +
                                            "다음 화면의 오른쪽 하단 버튼을 누르면 옷의 정보를 알려줍니다. " +
                                            "다음 화면의 왼쪽 하단 버튼을 누르면 날씨 정보를 알려줍니다. " +
                                            "어플안의 모든 버튼을 길게 누르실 경우 어떤 버튼인지 음성으로 안내됩니다. " +
                                            "다음 화면으로 넘어가시려면 화면을 빠르게 두번 클릭해주세요. " +
                                            "다시 듣기를 원하시면 화면을 한 번 클릭해주세요.";
                                    tts.speak(speechtext, TextToSpeech.QUEUE_FLUSH, null);
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    };
                    thread.start();
                } else {
                    waitDouble = true;
                    //double click event
                    Intent intent = new Intent(getApplicationContext(), DetectorActivity.class);
                    startActivity(intent);
                    tts.stop();
                    finish();
                }

            case MotionEvent.ACTION_MOVE:
                //터치 후 손가락을 움직일 때 할 일
                break;
            case MotionEvent.ACTION_UP:
                //손가락을 화면에서 뗄 때 할 일
                break;
            case MotionEvent.ACTION_CANCEL:
                // 터치가 취소될 때 할 일
                break;
            default:
                break;
        }
        return true;
    }

    // 마지막으로 뒤로가기 버튼을 눌렀던 시간 저장
    private long backKeyPressedTime = 0;
    // 첫 번째 뒤로가기 버튼을 누를때 표시
    private Toast toast;

    @Override
    public void onBackPressed() {
        //두번 눌러 종료
        // 2000 milliseconds = 2 seconds
        if (System.currentTimeMillis() > backKeyPressedTime + 5000) {
            backKeyPressedTime = System.currentTimeMillis();
            toast = Toast.makeText(this, "\'뒤로\' 버튼을 한번 더 누르시면 종료됩니다.", Toast.LENGTH_SHORT);
            toast.show();
            tts.setPitch(1.0f);         // 음성 톤을 2.0배 올려준다.
            tts.setSpeechRate(1.0f);    // 읽는 속도는 기본 설정
            tts.speak("\'뒤로\' 버튼을 한번 더 누르시면 종료됩니다.", TextToSpeech.QUEUE_FLUSH, null);
            return;
        }

        if (System.currentTimeMillis() <= backKeyPressedTime + 5000) {
            finish();
            toast.cancel();
        }

    }

}