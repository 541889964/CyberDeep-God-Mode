package com.cyber.god;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.mozilla.geckoview.*;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private GeckoView geckoView;
    private GeckoSession session;
    private static GeckoRuntime runtime;
    private TextView tvStatus;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private SharedPreferences sp;
    private int baseMinutes = 5;
    private int offsetMinutes = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        sp = getSharedPreferences("cyber_config", MODE_PRIVATE);
        baseMinutes = sp.getInt("base_minutes", 5);
        offsetMinutes = sp.getInt("offset_minutes", 3);

        geckoView = findViewById(R.id.geckoView);
        tvStatus = findViewById(R.id.tvStatus);

        // 启动前台保活服务
        startService(new Intent(this, CyberKeepAliveService.class));

        if (runtime == null) runtime = GeckoRuntime.create(this);
        session = new GeckoSession();

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(@NonNull GeckoSession session, String title) {
                if (title != null && title.startsWith("STOLEN_HEADERS:")) {
                    String headersJson = title.substring(15);
                    mainHandler.post(() -> saveHeadersToDownload(headersJson));
                }
            }
        });

        session.open(runtime);
        geckoView.setSession(session);
        session.loadUri("https://chat.deepseek.com");

        // 延迟 3 秒注入拦截脚本
        mainHandler.postDelayed(this::injectStealScript, 3000);

        // 手动抓取按钮
        Button btnManual = findViewById(R.id.btnManual);
        btnManual.setOnClickListener(v -> {
            Toast.makeText(this, "正在手动触发抓取...", Toast.LENGTH_SHORT).show();
            String js = 
                "let ta = document.querySelector('textarea');" +
                "if (ta) {" +
                "  ta.focus();" +
                "  let setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;" +
                "  setter.call(ta, '1（只回复好）');" +
                "  ta.dispatchEvent(new Event('input', { bubbles: true }));" +
                "  setTimeout(() => ta.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true})), 300);" +
                "}";
            session.loadUri("javascript:" + js);
        });

        // 参数设置按钮
        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置自动抓取频率");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText etBase = new EditText(this);
        etBase.setHint("基准时间（分钟）");
        etBase.setText(String.valueOf(baseMinutes));
        etBase.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(etBase);

        final EditText etOffset = new EditText(this);
        etOffset.setHint("偏移幅度（分钟）");
        etOffset.setText(String.valueOf(offsetMinutes));
        etOffset.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(etOffset);

        builder.setView(layout);
        builder.setPositiveButton("保存并重载引擎", (dialog, which) -> {
            try {
                baseMinutes = Integer.parseInt(etBase.getText().toString());
                offsetMinutes = Integer.parseInt(etOffset.getText().toString());
                sp.edit().putInt("base_minutes", baseMinutes).putInt("offset_minutes", offsetMinutes).apply();
                Toast.makeText(this, "配置已保存，正在重启探针...", Toast.LENGTH_SHORT).show();
                session.loadUri("javascript:location.reload();");
            } catch (Exception e) {
                Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void injectStealScript() {
        long baseMs = baseMinutes * 60L * 1000L;
        long offsetMs = offsetMinutes * 60L * 1000L;

        String js = 
            "(function() {" +
            "  if (window.cyberHooked) return;" +
            "  window.cyberHooked = true;" +
            "  let origFetch = window.fetch;" +
            "  window.fetch = async function() {" +
            "    let url = arguments[0];" +
            "    let options = arguments[1];" +
            "    if (typeof url === 'string' && url.includes('/chat/completion')) {" +
            "      if (options && options.headers) {" +
            "        let headersObj = {};" +
            "        if (typeof options.headers.forEach === 'function') {" +
            "           options.headers.forEach((v, k) => { headersObj[k] = v; });" +
            "        } else { headersObj = options.headers; }" +
            "        let headersStr = JSON.stringify(headersObj);" +
            "        document.title = 'STOLEN_HEADERS:' + headersStr;" +
            "      }" +
            "    }" +
            "    return origFetch.apply(this, arguments);" +
            "  };" +
            "  let probeCount = 0;" +
            "  function scheduleNext() {" +
            "    let base = " + baseMs + ";" + 
            "    let offset = " + offsetMs + ";" + 
            "    let rand = (Math.random() + Math.random()) / 2;" + 
            "    let delay = Math.max(60000, base - offset + rand * (offset * 2));" +
            "    setTimeout(function() {" +
            "      let messages = document.querySelectorAll('.ds-markdown');" +
            "      if (messages.length > 8 || probeCount > 10) {" +
            "        let newChatBtns = document.querySelectorAll('div, button');" +
            "        let cleared = false;" +
            "        for (let i=0; i<newChatBtns.length; i++) {" +
            "          if (newChatBtns[i].innerText.includes('新建对话') || newChatBtns[i].getAttribute('aria-label') === '新建对话') {" +
            "            newChatBtns[i].click(); cleared = true; break;" +
            "          }" +
            "        }" +
            "        if (!cleared) { location.reload(); }" +
            "        probeCount = 0;" +
            "      }" +
            "      let isGenerating = false;" +
            "      let btns = document.querySelectorAll('div[role=\\'button\\'], button');" +
            "      for(let i=0; i<btns.length; i++) {" +
            "        let t = btns[i].innerText || '';" +
            "        let a = btns[i].getAttribute('aria-label') || '';" +
            "        if (t.includes('停止') || a.includes('停止')) { isGenerating = true; break; }" +
            "      }" +
            "      let ta = document.querySelector('textarea');" +
            "      if (ta && !ta.disabled && !isGenerating) {" +
            "        let texts = ['1', '.', 'ok', 'hi', '在吗'];" +
            "        let txt = texts[Math.floor(Math.random() * texts.length)] + ' （请只回复一个\\'好\\'字）';" +
            "        ta.focus();" +
            "        let setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;" +
            "        setter.call(ta, txt);" +
            "        ta.dispatchEvent(new Event('input', { bubbles: true }));" +
            "        setTimeout(function() {" +
            "          ta.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));" +
            "          probeCount++;" +
            "        }, 300);" +
            "      }" +
            "      scheduleNext();" +
            "    }, delay);" +
            "  }" +
            "  scheduleNext();" +
            "})();";
        session.loadUri("javascript:" + js);
        tvStatus.setText("✅ 引擎已就绪");
    }

    private void saveHeadersToDownload(String headersJson) {
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) downloadDir.mkdirs();
            File file = new File(downloadDir, "DeepSeek_Latest_Headers.json");
            FileWriter writer = new FileWriter(file);
            writer.write(headersJson);
            writer.close();
            
            new Thread(() -> {
                try {
                    String cmd = "su -c \"cmd notification post -S bigtext -t 'CyberDeep' 'Tag' '完美签名已抓取并更新！'\"";
                    Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                } catch (Exception ignored) {}
            }).start();

            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvStatus.setText("✅ 签名已更新 " + time);
        } catch (Exception ignored) {}
    }
    
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
