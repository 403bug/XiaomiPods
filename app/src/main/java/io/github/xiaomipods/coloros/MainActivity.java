package io.github.xiaomipods.coloros;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int padding = (int) (22 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("XiaomiPods for ColorOS");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title, matchWrap());

        TextView status = new TextView(this);
        status.setText("在系统蓝牙详情页内直接控制小米耳机，不安装小米耳机 App。");
        status.setTextSize(16);
        status.setPadding(0, padding / 2, 0, padding);
        content.addView(status, matchWrap());

        addSection(content, "使用步骤",
                "1. 在 LSPosed 中启用本模块。\n"
                + "2. 作用域勾选「无线设置」com.oplus.wirelesssettings。\n"
                + "3. 首次启用后重启一次，或强制停止 com.oplus.wirelesssettings。\n"
                + "4. 停用或卸载小米耳机 App com.mi.earphone，避免它占用耳机控制通道。\n"
                + "5. 连接小米耳机，进入 设置 → 蓝牙 → 已配对设备 → 耳机功能。");

        addSection(content, "当前适配",
                "ColorOS 16.0.0.201(CN01)（WirelessSettings 16.6.0）\n"
                + "小米开放式耳机（VID 2717 / PID 507F）\n"
                + "功能：电量、固件、左右耳单击/双击/三击/长按、音效、双设备连接。");

        addSection(content, "说明",
                "协议实现直接运行在系统设置进程内，复用系统蓝牙权限，"
                + "因此本 App 不需要申请附近设备权限。若页面显示「连接失败」，"
                + "通常是耳机控制通道已被小米耳机 App 占用，停止该 App 后再试。");

        Button open = new Button(this);
        open.setText("打开蓝牙设置");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = padding;
        content.addView(open, buttonParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private void addSection(LinearLayout parent, String heading, String body) {
        int gap = (int) (16 * getResources().getDisplayMetrics().density);
        TextView title = new TextView(this);
        title.setText(heading);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, gap, 0, gap / 3);
        parent.addView(title, matchWrap());

        TextView text = new TextView(this);
        text.setText(body);
        text.setTextSize(15);
        text.setLineSpacing(0, 1.2f);
        parent.addView(text, matchWrap());
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
