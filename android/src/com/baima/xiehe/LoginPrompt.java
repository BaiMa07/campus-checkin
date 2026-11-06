package com.baima.xiehe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 学号 + 密码 输入框。
 *
 * 和电脑版一样：只要填了这两项，后面的切标签、填表、滑块、登录全部由程序自己完成。
 * 密码用 Android Keystore 加密存本机，会话过期时点通知就能自动重登。
 */
public class LoginPrompt {

    public static void show(final Activity act, final long accountId) {
        final Account old = accountId > 0 ? AccountStore.get(act).get(accountId) : null;

        int pad = (int) (act.getResources().getDisplayMetrics().density * 20);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etNo = new EditText(act);
        etNo.setHint("学号");
        etNo.setSingleLine(true);
        etNo.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (old != null && old.studentNo != null) etNo.setText(old.studentNo);
        box.addView(etNo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText etPwd = new EditText(act);
        etPwd.setHint("密码");
        etPwd.setSingleLine(true);
        // 用普通文本框 + 圆点变换，故意不用 PASSWORD 变体：
        // 否则 vivo/小米的"密码保险箱"会弹窗问要不要更新密码，把自动登录挡掉
        etPwd.setInputType(InputType.TYPE_CLASS_TEXT);
        etPwd.setTransformationMethod(PasswordTransformationMethod.getInstance());
        etPwd.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            etPwd.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        box.addView(etPwd, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tip = new TextView(act);
        tip.setText("点「开始登录」后全自动：自动填账号密码、自动拖滑块，10 秒左右完成。"
                + "密码只加密保存在本机，用来在登录过期时自动重登。");
        tip.setTextSize(12);
        tip.setPadding(0, pad / 3, 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(act)
                .setTitle(old == null ? "添加账号" : "重新登录「" + old.title() + "」")
                .setView(box)
                .setPositiveButton("开始登录", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String u = etNo.getText().toString().trim();
                        String p = etPwd.getText().toString();
                        Intent i = new Intent(act, LoginActivity.class);
                        if (u.length() > 0 && p.length() > 0) {
                            i.putExtra(LoginActivity.EXTRA_USER, u);
                            i.putExtra(LoginActivity.EXTRA_PWD, p);
                            i.putExtra(LoginActivity.EXTRA_AUTO, true);
                        }
                        if (accountId > 0) {
                            i.putExtra(LoginActivity.EXTRA_ACCOUNT_ID, accountId);
                        }
                        act.startActivity(i);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
