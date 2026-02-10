package com.app.minirat;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // This activity is completely hidden
        // No UI is created
        finish(); // Finish immediately
    }
}
