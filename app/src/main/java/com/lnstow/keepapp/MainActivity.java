package com.lnstow.keepapp;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements View.OnLongClickListener {
    private static final String TAG = "MainActivity";
    static boolean DEBUG = false;
    static boolean STOP = true;
    static boolean SCREEN_ON = true;
    static int SLEEP_TIME = 2;
    static ExecutorService fixedThread = null;
    static Thread notifyThread = null;
    static String pkgStr = "";
    static String[] cmd = new String[2];
    static ActivityManager am;
    Handler handler;
    ScreenStatusReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.btnExit).setOnLongClickListener(this);

        am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        handler = new mHandle(this);
        pkgStr = readFile(new File(getExternalFilesDir(null), "pkg"));
        EditText editText = findViewById(R.id.pkgList);
        editText.setText(pkgStr);

        receiver = new ScreenStatusReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(receiver, intentFilter);
    }

    public static void resetCmd(ArrayList<String> pidList) {
        StringBuilder cat = new StringBuilder(), echo = new StringBuilder();
        for (int i = 0; i < pidList.size(); i++) {
            cat.append("cat /proc/").append(pidList.get(i)).append("/oom_adj\n");
            echo.append("echo -16 > /proc/").append(pidList.get(i)).append("/oom_adj\n");
        }
        cmd[0] = cat.toString();
        cmd[1] = echo.toString();
    }

    public static ArrayList<String> getPid(char[] buff) {
        ArrayList<String> pidList = new ArrayList<>();
        char[] pid = new char[10];
        int start = -1;
        boolean newLine = true;
        for (int i = 0; buff[i] != '\0'; i++) {
            if (buff[i] == '\n') {
                pidList.add(new String(pid).substring(0, start));
                pid = new char[10];
                start = -1;
                newLine = true;
                continue;
            }
            if (!newLine) continue;
            if (buff[i] == ' ') {
                if (start == -1) start++;
                else if (start > 0) newLine = false;
            } else if (start != -1)
                pid[start++] = buff[i];
        }
        return pidList;
    }

    public static ArrayList<String> getPid(String[] pkgList) {
        //如果包名对应的应用没有运行，执行ps |grep命令就没有输出
        //这时read()和readLine()方法都会阻塞线程
        //1.可以用Future设置超时中断任务
        //2.可以先用br.ready()判断结果
        ArrayList<String> pidList = new ArrayList<>();
        if (pkgList[0].equals("")) return pidList;
        try {
            Process process = new ProcessBuilder("su").redirectErrorStream(true).start();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder cmd = new StringBuilder();
            for (int i = 0; i < pkgList.length; i++)
                cmd.append("ps |grep ").append(pkgList[i]).append('\n');
            char[] buff = new char[pkgList.length * 600];
            bw.write(cmd.toString());
            bw.flush();
            //让给process执行，再获得结果
            TimeUnit.MILLISECONDS.sleep(300);
            if (br.ready()) {
                br.read(buff);
                pidList = getPid(buff);
            }
            br.close();
            bw.close();
            process.destroy();
            return pidList;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public static void calculateTime(long availMem, long totalMem) {
        float percent = availMem * 1.0f / totalMem;
        float result = SLEEP_TIME;
        if (SCREEN_ON) {//y:1-5,x:
            if (totalMem > 6000)//0.35-0.475
                result = 32 * percent - 10.2f;
            else if (totalMem > 4000)//0.3-0.45
                result = 26.67f * percent - 7;
            else result = 20 * percent - 3;//0.2-0.4
            result = result < 1 ? 1 : result;
            result = result > 5 ? 5 : result;
        } else if (result < 11)
            result++;
        else {//y:10-300
            if (totalMem > 6000)//0.35-0.475
                result = 2320 * percent - 802;
            else if (totalMem > 4000)//0.3-0.45
                result = 1933.34f * percent - 570;
            else result = 1450 * percent - 280;//0.2-0.4
            result = result < 10 ? 10 : result;
            result = result > 300 ? 300 : result;
        }
        SLEEP_TIME = (int) (result + 0.25f);
//        Log.d(TAG, "calculateTime: " + availMem
//                + "  " + totalMem + "  " + percent);
//        Log.d(TAG, "calculateTime: " + SLEEP_TIME);
    }

    public void clickBtn(View view) {
        if (view.getId() == R.id.btnExit) finish();
        else if (view.getId() == R.id.btnStop) {
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
            stopThread();
        } else {//start
            if (fixedThread == null || fixedThread.isShutdown()) {
                STOP = false;
                fixedThread = Executors.newFixedThreadPool(4);
                fixedThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        pkgStr = getEditText();
                        updatePid(handler, true);
                        startThread(false);
                    }
                });
                fixedThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        Process process = null;
                        BufferedWriter bw = null;
                        BufferedReader br = null;
                        try {
                            process = new ProcessBuilder("su")
                                    .redirectErrorStream(true).start();
                            bw = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
                            br = new BufferedReader(new InputStreamReader(process.getInputStream()));
                            char[] buff = new char[500];
                            int count;
                            while (!STOP) {
//                                Log.d(TAG, "run: exec wait");
                                synchronized (TAG) {
                                    TAG.wait();
                                }
                                bw.write(DEBUG ? (cmd[0] + cmd[1]) : cmd[1]);
                                bw.flush();
                                if (br.ready()) {
                                    count = br.read(buff);
                                    if (DEBUG) {
                                        Message message = Message.obtain();
                                        message.what = 3;
                                        message.obj = new String(buff).substring(0, count);
                                        handler.sendMessage(message);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                if (br != null) br.close();
                                if (bw != null) bw.close();
                                if (process != null) process.destroy();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });
                fixedThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            while (true) {
                                TimeUnit.MINUTES.sleep(5);
                                updatePid(null, false);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            } else {
                //fixedThread剩余线程数是1，确保串行
                fixedThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        pkgStr = getEditText();
                        updatePid(handler, false);
                    }
                });
            }
        }
    }

    public static void startThread(boolean interrupt) {
        synchronized (TAG) {
            TAG.notify();
        }
        if (STOP || (SLEEP_TIME < 3 && interrupt)) return;
        else SLEEP_TIME = 1;
        if (notifyThread != null)
            notifyThread.interrupt();
        fixedThread.execute(new Runnable() {
            @Override
            public void run() {
                notifyThread = Thread.currentThread();
                try {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    while (!STOP) {
//                        Log.d(TAG, "run: notify wake");
                        synchronized (TAG) {
                            TAG.notify();
                        }
                        TimeUnit.SECONDS.sleep(SLEEP_TIME);
                        am.getMemoryInfo(mi);
                        calculateTime(mi.availMem / 0x100000L,
                                mi.totalMem / 0x100000L);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public static void stopThread() {
        STOP = true;
        if (fixedThread != null && !fixedThread.isShutdown()) {
            synchronized (TAG) {
                TAG.notify();
            }
            notifyThread = null;
            fixedThread.shutdownNow();
            fixedThread = null;
        }
    }

    public static void updatePid(Handler handler, boolean first) {
        ArrayList<String> pidList = getPid(pkgStr.split("\n"));
        resetCmd(pidList);
        if (handler != null) {
            Message message = Message.obtain();
            message.what = first ? 1 : 2;
            handler.sendMessage(message);
        }
    }

    public String getEditText() {
        EditText editText = findViewById(R.id.pkgList);
        String str = editText.getText().toString().replaceAll(" ", "");
        return str.replaceAll("\\B\\n+", "");
    }

    public void saveFile(File file) {
        try {
            if (!file.exists())
                file.createNewFile();
            BufferedWriter bw = new BufferedWriter(new FileWriter(file));
            bw.write(getEditText());
            bw.flush();
            bw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public String readFile(File file) {
        try {
            if (!file.exists()) {
                file.createNewFile();
                BufferedWriter bw = new BufferedWriter(new FileWriter(file));
                bw.write("com.lnstow.keepapp\n");
                bw.flush();
                bw.close();
            }
            StringBuilder sb = new StringBuilder();
            String str;
            BufferedReader br = new BufferedReader(new FileReader(file));
            while ((str = br.readLine()) != null)
                sb.append(str).append('\n');
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static class mHandle extends Handler {
        WeakReference<MainActivity> weakReference;

        public mHandle(MainActivity activity) {
            weakReference = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            MainActivity activity = weakReference.get();
            if (activity == null) return;
            switch (msg.what) {
                case 1:
                    Toast.makeText(activity, "已开始", Toast.LENGTH_SHORT).show();
                    break;
                case 2:
                    Toast.makeText(activity, "已刷新", Toast.LENGTH_SHORT).show();
                    break;
                case 3:
                    ((TextView) activity.findViewById(R.id.tvDebug))
                            .append(new StringBuilder().append("sleep time:").append(SLEEP_TIME)
                                    .append("    oom_adj:").append(msg.obj));
                    return;
            }
            EditText editText = activity.findViewById(R.id.pkgList);
            editText.setText(pkgStr);
        }

    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level > TRIM_MEMORY_UI_HIDDEN) {
//            Log.d(TAG, "onTrimMemory: " + level);
            startThread(true);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveFile(new File(getExternalFilesDir(null), "pkg"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
        stopThread();
        am = null;
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(false);
    }

    @Override
    public boolean onLongClick(View v) {
        if (v.getId() != R.id.btnExit) return false;
        DEBUG = !DEBUG;
        Toast.makeText(this, "DEBUG " + (DEBUG ? "ON" : "OFF"),
                Toast.LENGTH_SHORT).show();
        TextView textView = findViewById(R.id.tvDebug);
        textView.setVisibility(DEBUG ? View.VISIBLE : View.GONE);
        if (!DEBUG) textView.setText("");
        return true;
    }

}
