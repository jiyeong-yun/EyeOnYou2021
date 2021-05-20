package org.tensorflow.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class RecActivity extends Activity {
    TextView text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rec);

        text = findViewById(R.id.text);
    }
}