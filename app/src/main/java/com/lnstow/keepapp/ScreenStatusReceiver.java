package com.lnstow.keepapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ScreenStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
//            System.out.println("screen on");
            MainActivity.SCREEN_ON = true;
            MainActivity.startThread(true);
        } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
//            System.out.println("screen off");
            MainActivity.SCREEN_ON = false;
        }
    }
}
