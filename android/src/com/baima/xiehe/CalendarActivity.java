package com.baima.xiehe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Toast;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 签到日历。
 *
 * 绿：那天签上了    红：过去的日子没签    灰白：今天和以后（还没到）
 * 顶部可以切月份，也可以只看某一个账号。
 */
public class CalendarActivity extends Activity {

    private Calendar month = Calendar.getInstance();
    private long accountId = 0;

    private TextView tvMonth, tvLegend;
    private GridLayout grid;
    private LinearLayout weekRow;
    private Spinner spAccount;
    private boolean spinnerReady;

    private static final int GREEN = 0xFF22C55E;   // 已签到
    private static final int MISS = 0xFFEF4444;    // 缺勤：过去没签的
    private static final int GRAY = 0xFFE9EAEE;    // 还没到：浅灰
    private static final int TODAY = 0xFF2563EB;   // 今天：蓝框

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_calendar);
        Logs.init(this);
        month.set(Calendar.DAY_OF_MONTH, 1);

        tvMonth = (TextView) findViewById(R.id.tvMonth);
        tvLegend = (TextView) findViewById(R.id.tvLegend);
        grid = (GridLayout) findViewById(R.id.grid);
        weekRow = (LinearLayout) findViewById(R.id.weekRow);
        spAccount = (Spinner) findViewById(R.id.spAccount);

        ((Button) findViewById(R.id.btnPrev)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                month.add(Calendar.MONTH, -1);
                render();
            }
        });
        ((Button) findViewById(R.id.btnNext)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                month.add(Calendar.MONTH, 1);
                render();
            }
        });
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        ((Button) findViewById(R.id.btnImport)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { importHistory(); }
        });

        buildWeekHeader();
        setupAccounts();
        render();
    }

    private void buildWeekHeader() {
        weekRow.removeAllViews();
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (String n : names) {
            TextView t = new TextView(this);
            t.setText(n);
            t.setTextSize(12);
            t.setTextColor(0xFF9CA3AF);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            int m = (int) (getResources().getDisplayMetrics().density * 2);
            lp.setMargins(m, 0, m, 0);
            weekRow.addView(t, lp);
        }
    }

    private void setupAccounts() {
        List<String> names = new ArrayList<String>();
        final List<Long> ids = new ArrayList<Long>();
        names.add("全部账号");
        ids.add(0L);
        for (Account a : AccountStore.get(this).list()) {
            names.add(a.title());
            ids.add(a.id);
        }
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAccount.setAdapter(ad);
        spAccount.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                accountId = ids.get(pos);
                if (spinnerReady) render();
            }

            public void onNothingSelected(AdapterView<?> p) { }
        });
        spinnerReady = true;
    }

    private void render() {
        tvMonth.setText(new SimpleDateFormat("yyyy年M月", Locale.CHINA).format(month.getTime()));
        final String prefix = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(month.getTime());
        final long aid = accountId;
        new Thread(new Runnable() {
            public void run() {
                AccountStore st = AccountStore.get(CalendarActivity.this);
                final Map<String, Integer> map = st.monthHistory(aid, prefix);
                final String first = st.firstDay(aid);
                runOnUiThread(new Runnable() {
                    public void run() { draw(map, prefix, first); }
                });
            }
        }).start();
    }

    private void draw(Map<String, Integer> map, String prefix, String firstDay) {
        grid.removeAllViews();
        int cellH = (int) (getResources().getDisplayMetrics().density * 46);

        Calendar first = (Calendar) month.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int dow = first.get(Calendar.DAY_OF_WEEK);          // 周日=1
        int offset = (dow + 5) % 7;                          // 周一开头

        for (int i = 0; i < offset; i++) {
            grid.addView(blank(cellH));
        }

        int max = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar today = Calendar.getInstance();
        String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(today.getTime());

        int signed = 0;
        int missed = 0;
        for (int d = 1; d <= max; d++) {
            String day = String.format(Locale.US, "%s-%02d", prefix, d);
            Integer ok = map.get(day);
            boolean isSigned = ok != null && ok == 1;
            if (isSigned) signed++;
            boolean isPast = day.compareTo(todayStr) < 0;
            // 开始记录之前的日子不判漏签，不然新装的人整月都是红的
            boolean tracked = firstDay != null && firstDay.length() > 0 && day.compareTo(firstDay) >= 0;
            if (!tracked) isPast = false;

            int bg;
            int fg;
            boolean isToday = day.equals(todayStr);
            if (isSigned) {
                bg = GREEN;
                fg = Color.WHITE;
            } else if (isPast) {
                bg = MISS;
                fg = Color.WHITE;
                missed++;
            } else {
                bg = GRAY;
                fg = 0xFF9CA3AF;
            }

            final String dayKey = day;
            TextView t = new TextView(this);
            t.setText(String.valueOf(d));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showDay(dayKey); }
            });
            t.setTextSize(13);
            t.setTextColor(fg);
            t.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setColor(bg);
            g.setCornerRadius(getResources().getDisplayMetrics().density * 8);
            if (isToday) {
                // 今天加一圈蓝框，一眼能找到
                g.setStroke((int) (getResources().getDisplayMetrics().density * 3), TODAY);
                t.setTextColor(isSigned || isPast ? Color.WHITE : 0xFF1D4ED8);
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
            t.setBackground(g);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = cellH;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(2, 2, 2, 2);
            grid.addView(t, lp);
        }

        tvLegend.setText("本月已签 " + signed + " 天"
                + (missed > 0 ? "　缺勤 " + missed + " 天" : "")
                + "\n绿=已签到　红=缺勤　蓝框=今天　灰=还没到");
    }

    /** 点某一天，看当天各个账号的签到明细 */
    private void showDay(final String day) {
        new Thread(new Runnable() {
            public void run() {
                final List<String> rows = AccountStore.get(CalendarActivity.this)
                        .dayDetail(accountId, day);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (rows.isEmpty()) {
                            Toast.makeText(CalendarActivity.this,
                                    day + " 没有签到记录", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        StringBuilder sb = new StringBuilder();
                        for (String r : rows) sb.append(r).append("\n\n");
                        new AlertDialog.Builder(CalendarActivity.this)
                                .setTitle(day)
                                .setMessage(sb.toString().trim())
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 从学校系统把最近 6 个月的考勤拉下来写进日历 */
    private void importHistory() {
        final List<Account> all = AccountStore.get(this).list();
        final List<Account> use = new ArrayList<Account>();
        for (Account a : all) {
            if (accountId > 0) {
                if (a.id == accountId) use.add(a);
            } else if (a.cookie != null && a.cookie.length() > 0) {
                use.add(a);
            }
        }
        if (use.isEmpty()) {
            Toast.makeText(this, "没有可用的账号，先登录一个", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在从学校导考勤，可能要十几秒…", Toast.LENGTH_SHORT).show();

        // 固定从"当前月份"往回导，跟日历正在看哪个月无关
        Calendar now = Calendar.getInstance();
        final int baseYear = now.get(Calendar.YEAR);
        final int baseMonth = now.get(Calendar.MONTH) + 1;

        new Thread(new Runnable() {
            public void run() {
                AccountStore st = AccountStore.get(CalendarActivity.this);
                int total = 0;
                int failed = 0;
                for (Account a : use) {
                    for (int k = 0; k < 6; k++) {
                        Calendar m2 = Calendar.getInstance();
                        m2.set(baseYear, baseMonth - 1, 1);
                        m2.add(Calendar.MONTH, -k);
                        int y = m2.get(Calendar.YEAR);
                        int mo = m2.get(Calendar.MONTH) + 1;
                        List<KqApi.Hist> hs = KqApi.history(a.cookie, a.ua, y, mo);
                        if (hs.isEmpty()) {
                            failed++;
                            continue;
                        }
                        for (KqApi.Hist h : hs) {
                            st.record(a.id, h.day, true,
                                    "学校记录 " + h.time.replace(h.day + " ", "") + " · " + h.place);
                            total++;
                        }
                    }
                }
                final int n = total;
                final int f = failed;
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(CalendarActivity.this,
                                "已导入 " + n + " 条记录" + (f > 0 ? "（" + f + " 个月没数据）" : ""),
                                Toast.LENGTH_LONG).show();
                        Logs.add("导入学校考勤：" + n + " 条");
                        render();
                    }
                });
            }
        }).start();
    }

    private View blank(int h) {
        TextView t = new TextView(this);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = h;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        t.setLayoutParams(lp);
        return t;
    }
}
