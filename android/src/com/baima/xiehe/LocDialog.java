package com.baima.xiehe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 签到位置设置。
 *
 * acc == null：主界面的"默认位置"，对所有没单独设置的账号生效。
 * acc != null：只改这一个账号的位置。
 */
public class LocDialog {

    public static void show(final Activity act, final Account acc) {
        show(act, acc, null);
    }

    public static void show(final Activity act, final Account acc, final Runnable after) {
        final AccountStore st = AccountStore.get(act);
        final Prefs pf = Prefs.get(act);

        boolean on = acc != null ? acc.locOn : pf.locOn();
        String name = acc != null ? acc.locName : pf.locName();
        double lat = acc != null ? acc.locLat : pf.locLat();
        double lng = acc != null ? acc.locLng : pf.locLng();

        int pad = (int) (act.getResources().getDisplayMetrics().density * 20);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView tvNow = new TextView(act);
        tvNow.setTextSize(12);
        if (acc == null) {
            tvNow.setText(on ? ("当前默认：" + name + "（" + lat + ", " + lng + "）")
                    : "当前默认：按宿舍自动匹配打卡点");
        } else {
            tvNow.setText(on ? ("该账号位置：" + name + "（" + lat + ", " + lng + "）")
                    : "该账号位置：跟随默认（按宿舍自动匹配）");
        }
        box.addView(tvNow);

        final EditText etName = new EditText(act);
        etName.setHint("打卡点名称，例如 X号公寓");
        etName.setSingleLine(true);
        if (on) etName.setText(name);
        box.addView(etName);

        final EditText etLat = new EditText(act);
        etLat.setHint("纬度，例如 36.740000");
        etLat.setSingleLine(true);
        etLat.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (on) etLat.setText(String.valueOf(lat));
        box.addView(etLat);

        final EditText etLng = new EditText(act);
        etLng.setHint("经度，例如 117.278000");
        etLng.setSingleLine(true);
        etLng.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (on) etLng.setText(String.valueOf(lng));
        box.addView(etLng);

        Button btnPick = new Button(act);
        btnPick.setText("从学校的打卡点列表里选");
        box.addView(btnPick);
        btnPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pick(act, acc, etName, etLat, etLng);
            }
        });

        TextView tip = new TextView(act);
        tip.setTextSize(12);
        tip.setText("不单独设置就是「自动」：优先沿用这个账号上一次签到成功的位置，"
                + "没有记录时按宿舍名匹配打卡点。签到提交的是名称 + 经纬度，服务端按距离算，30 米内有效。");
        tip.setPadding(0, pad / 3, 0, 0);
        box.addView(tip);

        new AlertDialog.Builder(act)
                .setTitle(acc == null ? "默认签到位置" : ("签到位置 · " + acc.title()))
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = etName.getText().toString().trim();
                        String la = etLat.getText().toString().trim();
                        String ln = etLng.getText().toString().trim();
                        if (n.length() == 0 || la.length() == 0 || ln.length() == 0) {
                            toast(act, "名称、纬度、经度都要填");
                            return;
                        }
                        double a1, a2;
                        try {
                            a1 = Double.parseDouble(la);
                            a2 = Double.parseDouble(ln);
                        } catch (Exception e) {
                            toast(act, "经纬度格式不对");
                            return;
                        }
                        if (acc == null) {
                            pf.setLoc(true, n, a1, a2);
                            Logs.add("默认签到位置改为：" + n + "（" + a1 + ", " + a2 + "）");
                        } else {
                            acc.locOn = true;
                            acc.locName = n;
                            acc.locLat = a1;
                            acc.locLng = a2;
                            st.update(acc);
                            Logs.add("【" + acc.title() + "】签到位置改为：" + n + "（" + a1 + ", " + a2 + "）");
                        }
                        toast(act, "已设为：" + n);
                        if (after != null) after.run();
                    }
                })
                .setNeutralButton("恢复自动", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (acc == null) {
                            pf.setLoc(false, "", 0, 0);
                            Logs.add("默认签到位置：按宿舍自动匹配");
                        } else {
                            acc.locOn = false;
                            acc.locName = "";
                            acc.locLat = 0;
                            acc.locLng = 0;
                            st.update(acc);
                            Logs.add("【" + acc.title() + "】签到位置：跟随默认");
                        }
                        toast(act, "已恢复自动匹配");
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 拉一次接口，把学校给的打卡点列出来让用户选 */
    private static void pick(final Activity act, final Account acc,
                             final EditText etName, final EditText etLat, final EditText etLng) {
        Account use = acc;
        if (use == null || use.cookie == null || use.cookie.length() == 0) {
            use = null;
            List<Account> list = AccountStore.get(act).list();
            for (Account a : list) {
                if (a.cookie != null && a.cookie.length() > 0) { use = a; break; }
            }
        }
        if (use == null) {
            toast(act, "先登录一个账号才能读打卡点");
            return;
        }
        final Account src = use;
        toast(act, "正在读取打卡点…");
        new Thread(new Runnable() {
            public void run() {
                final KqApi.Resp r = KqApi.getKqInfo(src.cookie, src.ua);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (r.state != KqApi.State.OK || r.model == null
                                || r.model.points.isEmpty()) {
                            toast(act, "没读到打卡点：" + r.msg);
                            return;
                        }
                        final List<KqModel.Point> pts = r.model.points;
                        String[] items = new String[pts.size()];
                        for (int i = 0; i < pts.size(); i++) items[i] = pts.get(i).describe();
                        new AlertDialog.Builder(act)
                                .setTitle("选一个打卡点")
                                .setItems(items, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int which) {
                                        KqModel.Point p = pts.get(which);
                                        etName.setText(p.name);
                                        etLat.setText(String.valueOf(p.lat));
                                        etLng.setText(String.valueOf(p.lng));
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }
}
