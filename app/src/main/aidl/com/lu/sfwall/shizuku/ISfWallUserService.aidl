package com.lu.sfwall.shizuku;

import com.lu.sfwall.shizuku.ISfWallForegroundListener;

interface ISfWallUserService {
    void destroy() = 16777114;
    String setUidFirewallRule(int chain, int uid, int rule) = 1;
    String[] setUidFirewallRules(int chain, in int[] uids, in int[] rules) = 2;
    String getForegroundTask() = 3;
    void startForegroundWatch(ISfWallForegroundListener listener) = 4;
    void stopForegroundWatch() = 5;
    String setFirewallChainEnabled(boolean enabled) = 6;
}
