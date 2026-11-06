package com.baima.xiehe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 账号管理：列表展示 / 单账号签到 / 一键签到 / 添加 / 删除 / 间隔设置 */
public class AccountsActivity extends Activity {

    private static final int REQ_LOGIN_NEW = 3001;
    private static final int REQ_LOGIN_REFRESH = 3002;

    private LinearLayout container;
    private TextView tvSummary;
    private Button btnGap;
    private Button btnSignAll;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean signing;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_accounts);
        Logs.init(this);

        container = (LinearLayout) findViewById(R.id.container);
        tvSummary = (TextView) findViewById(R.id.tvSummary);
        btnGap = (Button) findViewById(R.id.btnGap);
        btnSignAll = (Button) findViewById(R.id.btnSignAll);

        findViewById(R.id.btnAddAccount).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LoginPrompt.show(AccountsActivity.this, 0);
            }
        });
        btnSignAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { signAll(); }
        });
        btnGap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showGapDialog(); }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        renderGap();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderGap();
        reload();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res == RESULT_OK) reload();
    }

    private void renderGap() {
        btnGap.setText("账号间隔：" + Prefs.get(this).gapSeconds() + " 秒");
    }

    private void showGapDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(Prefs.get(this).gapSeconds()));
        new AlertDialog.Builder(this)
                .setTitle("账号之间的签到间隔")
                .setMessage("多账号连续签到会隔开这么久再签下一个账号，防止风控。建议 3-10 秒。")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int v = 3;
                        try { v = Integer.parseInt(et.getText().toString().trim()); }
                        catch (Throwable ignored) { }
                        Prefs.get(AccountsActivity.this).setGapSeconds(v);
                        renderGap();
                        Logs.add("账号间隔已设为 " + Prefs.get(AccountsActivity.this).gapSeconds() + " 秒");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 读库 + 重绘列表 */
    private void reload() {
        new Thread(new Runnable() {
            public void run() {
                final List<Account> list = AccountStore.get(AccountsActivity.this).list();
                ui.post(new Runnable() {
                    public void run() { renderList(list); }
                });
            }
        }).start();
    }

    private void renderList(final List<Account> list) {
        container.removeAllViews();
        if (list.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(getString(R.string.acct_empty));
            tv.setTextColor(0xFF6B7280);
            tv.setTextSize(13);
            tv.setPadding(4, 10, 4, 10);
            container.addView(tv);
            tvSummary.setText("账号：0 个\n" + (signing ? "" : "点「添加账号」登录一次即可"));
            return;
        }

        LayoutInflater inf = LayoutInflater.from(this);
        for (final Account a : list) {
            View row = inf.inflate(R.layout.item_account, container, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            row.setLayoutParams(lp);

            TextView tvName = (TextView) row.findViewById(R.id.tvName);
            TextView tvSub = (TextView) row.findViewById(R.id.tvSub);
            TextView tvState = (TextView) row.findViewById(R.id.tvState);

            tvName.setText(a.title());
            StringBuilder sub = new StringBuilder();
            if (a.studentNo != null && a.studentNo.length() > 0) sub.append("学号 ").append(a.studentNo);
            if (a.dorm != null && a.dorm.length() > 0) {
                if (sub.length() > 0) sub.append("　");
                sub.append(a.dorm);
            }
            if (sub.length() == 0) sub.append("点这一行可以给该账号重新登录");
            if (a.locOn) {
                sub.append("　位置：").append(a.locName);
            } else if (a.lastLocLat != 0) {
                sub.append("　位置：自动（上次 ").append(a.lastLocName).append("）");
            } else {
                sub.append("　位置：自动（按宿舍）");
            }
            tvSub.setText(sub.toString());

            StringBuilder st = new StringBuilder("状态：").append(a.stateText());
            st.append("　上次：").append(SignCore.hhmm(a.lastTime));
            if (a.lastResult != null && a.lastResult.length() > 0) {
                st.append("\n").append(a.lastResult);
            }
            tvState.setText(st.toString());
            if (a.lastOk == Account.STATE_OK) tvState.setTextColor(0xFF15803D);
            else if (a.lastOk == Account.STATE_FAIL) tvState.setTextColor(0xFFB91C1C);
            else tvState.setTextColor(0xFF374151);

            row.findViewById(R.id.btnRowSign).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { signOne(a); }
            });
            row.findViewById(R.id.btnRowLoc).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    LocDialog.show(AccountsActivity.this, a, new Runnable() {
                        public void run() { reload(); }
                    });
                }
            });
            row.findViewById(R.id.btnRowDel).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmDelete(a); }
            });
            tvName.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { relogin(a); }
            });
            tvSub.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { relogin(a); }
            });
            View.OnLongClickListener edit = new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    showEdit(a);
                    return true;
                }
            };
            row.setOnLongClickListener(edit);
            tvName.setOnLongClickListener(edit);
            tvSub.setOnLongClickListener(edit);

            container.addView(row);
        }

        String extra = "";
        if (Prefs.get(this).gapSeconds() > 0) extra = "";
        int okN = 0;
        for (Account a : list) if (a.lastOk == Account.STATE_OK) okN++;
        tvSummary.setText("账号：共 " + list.size() + " 个，今日已签 " + okN + " 个\n"
                + "点「签到」只签该账号，「一键签到」按间隔依次签全部\n"
                + "点账号名重新登录（存过密码就全自动），长按可以改学号和密码" + extra);
    }

    private void relogin(Account a) {
        String pwd = Secure.decrypt(a.pwdEnc);
        if (pwd.length() > 0 && a.studentNo != null && a.studentNo.length() > 0) {
            // 存过密码：直接全自动重登，不用再输任何东西
            Intent i = new Intent(this, LoginActivity.class);
            i.putExtra(LoginActivity.EXTRA_ACCOUNT_ID, a.id);
            i.putExtra(LoginActivity.EXTRA_USER, a.studentNo);
            i.putExtra(LoginActivity.EXTRA_PWD, pwd);
            i.putExtra(LoginActivity.EXTRA_AUTO, true);
            startActivityForResult(i, REQ_LOGIN_REFRESH);
            toast("正在自动登录…");
        } else {
            LoginPrompt.show(this, a.id);
        }
    }

    /** 长按账号：改学号 / 改密码 */
    private void showEdit(final Account a) {
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etNo = new EditText(this);
        etNo.setHint("学号");
        etNo.setSingleLine(true);
        etNo.setInputType(InputType.TYPE_CLASS_NUMBER);
        etNo.setText(a.studentNo == null ? "" : a.studentNo);
        box.addView(etNo);

        final EditText etPwd = new EditText(this);
        etPwd.setHint("新密码（留空表示不修改）");
        etPwd.setSingleLine(true);
        etPwd.setInputType(InputType.TYPE_CLASS_TEXT);
        etPwd.setTransformationMethod(PasswordTransformationMethod.getInstance());
        etPwd.setSaveEnabled(false);
        box.addView(etPwd);

        TextView tip = new TextView(this);
        tip.setText("保存后学号和密码都会加密存在本机。填了密码会立刻自动登录一次。");
        tip.setTextSize(12);
        tip.setPadding(0, pad / 3, 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle("修改账号「" + a.title() + "」")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String no = etNo.getText().toString().trim();
                        String pwd = etPwd.getText().toString();
                        boolean changed = false;
                        if (no.length() > 0 && !no.equals(a.studentNo)) {
                            a.studentNo = no;
                            changed = true;
                        }
                        if (pwd.length() > 0) {
                            a.pwdEnc = Secure.encrypt(pwd);
                            changed = true;
                        }
                        if (!changed) {
                            toast("没有改动");
                            return;
                        }
                        AccountStore.get(AccountsActivity.this).update(a);
                        Logs.add("已修改账号「" + a.title() + "」"
                                + (pwd.length() > 0 ? "（学号+密码）" : "（学号）"));
                        reload();
                        if (pwd.length() > 0) {
                            toast("已保存，正在自动登录…");
                            relogin(a);
                        } else {
                            toast("已保存");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final Account a) {
        new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("确定删除「" + a.title() + "」吗？\n删除后需要重新登录才能再签到。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        AccountStore.get(AccountsActivity.this).delete(a.id);
                        Logs.add("已删除账号「" + a.title() + "」");
                        toast("已删除");
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void signOne(final Account a) {
        if (signing) {
            toast("正在签到中，请稍候");
            return;
        }
        signing = true;
        tvSummary.setText("正在签到：" + a.title() + " …");
        new Thread(new Runnable() {
            public void run() {
                final SignCore.Result r = SignCore.sign(AccountsActivity.this, a);
                ui.post(new Runnable() {
                    public void run() {
                        signing = false;
                        tvSummary.setText((r.ok ? "✅ " : "❌ ") + a.title() + "：" + r.msg);
                        reload();
                    }
                });
            }
        }).start();
    }

    /** 一键签到：遍历账号，账号之间按设定间隔延时 */
    private void signAll() {
        if (signing) {
            toast("正在签到中，请稍候");
            return;
        }
        final List<Account> list = new ArrayList<Account>(AccountStore.get(this).list());
        if (list.isEmpty()) {
            toast("还没有账号，先添加一个");
            return;
        }
        signing = true;
        btnSignAll.setEnabled(false);
        final int gap = Prefs.get(this).gapSeconds();

        new Thread(new Runnable() {
            public void run() {
                final List<String> lines = new ArrayList<String>();
                int okN = 0;
                for (int i = 0; i < list.size(); i++) {
                    final Account a = list.get(i);
                    final int idx = i;
                    ui.post(new Runnable() {
                        public void run() {
                            tvSummary.setText("正在签到 " + (idx + 1) + "/" + list.size()
                                    + "：" + a.title() + " …");
                        }
                    });
                    SignCore.Result r = SignCore.sign(AccountsActivity.this, a);
                    if (r.ok) okN++;
                    lines.add((r.ok ? "✅ " : "❌ ") + a.title() + "：" + r.msg);
                    ui.post(new Runnable() {
                        public void run() { reload(); }
                    });
                    // 最后一个不用等
                    if (i < list.size() - 1 && gap > 0) {
                        final int left = gap;
                        ui.post(new Runnable() {
                            public void run() {
                                tvSummary.setText("已签 " + (idx + 1) + "/" + list.size()
                                        + "，等待 " + left + " 秒后签下一个…");
                            }
                        });
                        try { Thread.sleep(gap * 1000L); } catch (InterruptedException ignored) { }
                    }
                }
                final int okFinal = okN;
                ui.post(new Runnable() {
                    public void run() {
                        signing = false;
                        btnSignAll.setEnabled(true);
                        StringBuilder sb = new StringBuilder();
                        sb.append("签到完成：成功 ").append(okFinal).append(" / ").append(list.size()).append('\n');
                        for (String s : lines) sb.append(s).append('\n');
                        tvSummary.setText(sb.toString().trim());
                        Prefs.get(AccountsActivity.this).setLastResult(
                                "多账号签到 成功 " + okFinal + "/" + list.size());
                        reload();
                    }
                });
            }
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
