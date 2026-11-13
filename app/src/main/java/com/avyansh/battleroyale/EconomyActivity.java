package com.avyansh.battleroyale;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Server-backed soft currency, daily login reward and one-match daily mission. */
public final class EconomyActivity extends ScreenActivity {
    private TextView balance, dailyStatus, missionStatus, ledger;
    private TextView dailyButton, missionButton;

    @Override protected String screenTitle() { return "Wallet & Rewards"; }

    @Override protected View buildContent() {
        LinearLayout root = Ui.col(this);
        LinearLayout wallet = Ui.panel(this);
        Ui.section(this, wallet, "Your balance");
        balance = Ui.label(this, "0 Coins     0 Diamonds", 22, Ui.TEXT, true);
        wallet.addView(balance);
        wallet.addView(Ui.label(this, "Balances are stored and validated by the game server.", 11, Ui.MUTED, false));
        root.addView(wallet, Ui.lp(this, -1, -2, 0, 0, 0, 0, 10));

        LinearLayout daily = Ui.panel(this);
        Ui.section(this, daily, "Daily login reward");
        dailyStatus = Ui.label(this, "Checking reward availability…", 13, Ui.TEXT, false);
        daily.addView(dailyStatus);
        dailyButton = Ui.button(this, "CLAIM DAILY REWARD", Ui.PRIMARY, new View.OnClickListener() {
            @Override public void onClick(View v) { ServerNet.claimDailyReward(); }
        });
        daily.addView(dailyButton, Ui.lp(this, -1, -2, 0, 10, 8, 10, 0));
        root.addView(daily, Ui.lp(this, -1, -2, 0, 0, 0, 0, 10));

        LinearLayout mission = Ui.panel(this);
        Ui.section(this, mission, "Daily mission");
        missionStatus = Ui.label(this, "Play one server-validated match · Reward 150 coins", 13, Ui.TEXT, false);
        mission.addView(missionStatus);
        missionButton = Ui.button(this, "CLAIM MISSION", Ui.SECONDARY, new View.OnClickListener() {
            @Override public void onClick(View v) { ServerNet.claimMissionReward(); }
        });
        mission.addView(missionButton, Ui.lp(this, -1, -2, 0, 10, 8, 10, 0));
        root.addView(mission, Ui.lp(this, -1, -2, 0, 0, 0, 0, 10));

        LinearLayout history = Ui.panel(this);
        Ui.section(this, history, "Recent transactions");
        ledger = Ui.label(this, "No transactions yet.", 12, Ui.MUTED, false);
        history.addView(ledger);
        root.addView(history, Ui.lp(this, -1, 0, 1f, 0, 0, 0, 0));
        return root;
    }

    @Override protected void onResume() {
        super.onResume();
        ServerNet.refreshEconomy();
    }

    @Override protected void onState() {
        ServerNet.EconomyState e = ServerNet.economy;
        if (balance == null || e == null) return;
        balance.setText(e.coins + " Coins     " + e.diamonds + " Diamonds");
        dailyStatus.setText(e.dailyAvailable ? "Available: 250 coins + 2 diamonds" : "Already claimed. Come back after 24 hours.");
        Ui.setEnabled(dailyButton, e.dailyAvailable);
        boolean canClaim = e.missionProgress >= e.missionTarget && !e.missionClaimed;
        missionStatus.setText(e.missionClaimed ? "Completed and claimed today." :
            (e.missionProgress >= e.missionTarget ? "Complete · Reward " + e.missionRewardCoins + " coins" :
            "Play one server-validated match (" + e.missionProgress + "/" + e.missionTarget + ") · Reward " + e.missionRewardCoins + " coins"));
        Ui.setEnabled(missionButton, canClaim);
        if (e.transactions.isEmpty()) ledger.setText("No transactions yet.");
        else {
            StringBuilder b = new StringBuilder();
            for (String line : e.transactions) b.append("• ").append(line).append('\n');
            ledger.setText(b.toString().trim());
        }
    }
}
