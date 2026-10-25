package com.v60.basslab;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class QuadDacManager {

    public static class DacStatus {
        public int leftImpedance = 0;
        public int rightImpedance = 0;
        public int headsetType = 0; // 0=None, 1=Normal, 2=High-Imp (2.0V), 3=Aux
        public boolean isBypass = true;
        public int currentFilter = 0;
        public String stateName = "Bypass";

        public String getModeDescription() {
            switch (headsetType) {
                case 1:
                    return "Normal (0.45 Vrms)";
                case 2:
                    return "High-Impedance (2.0 Vrms)";
                case 3:
                    return "Auxiliar (1.0 Vrms)";
                default:
                    return "Desconectado / Standby";
            }
        }
    }

    public static final String[] ESS_FILTERS = {
        "Sharp Roll-Off (Linear Phase)",
        "Slow Roll-Off (Linear Phase)",
        "Short Delay Sharp (Minimum Phase)",
        "Short Delay Slow (Minimum Phase)",
        "Super Slow Roll-Off",
        "Low Dispersion Filter",
        "ESS Custom Preset 6",
        "ESS Custom Preset 7",
        "ESS Custom Preset 8",
        "ESS HIFI Optimal (Default)",
        "ESS Filter 10",
        "ESS Filter 11",
        "ESS Filter 12"
    };

    public static DacStatus getStatus() {
        DacStatus status = new DacStatus();
        try {
            String out = runSuCommand(
                "tinymix 'HPHL Impedance'; " +
                "tinymix 'HPHR Impedance'; " +
                "tinymix 'Es9018 HEADSET TYPE'; " +
                "tinymix 'Es9218 Bypass'; " +
                "tinymix 'HIFI Custom Filter'; " +
                "tinymix 'Es9018 State'"
            );

            if (out != null) {
                String[] lines = out.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("HPHL Impedance:")) {
                        status.leftImpedance = parseFirstInt(line);
                    } else if (line.startsWith("HPHR Impedance:")) {
                        status.rightImpedance = parseFirstInt(line);
                    } else if (line.startsWith("Es9018 HEADSET TYPE:")) {
                        status.headsetType = parseFirstInt(line);
                    } else if (line.startsWith("Es9218 Bypass:")) {
                        status.isBypass = line.contains("On");
                    } else if (line.startsWith("HIFI Custom Filter:")) {
                        status.currentFilter = parseFirstInt(line);
                    } else if (line.startsWith("Es9018 State:")) {
                        if (line.contains(">")) {
                            int idx = line.indexOf('>');
                            status.stateName = line.substring(idx + 1).split("\\s+")[0];
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return status;
    }

    public static boolean setForceHighImpedance(boolean force) {
        try {
            if (force) {
                // Force ES9219 into High Impedance Mode (2.0 Vrms) & engage Hi-Fi path
                runSuCommand(
                    "tinymix 'Es9018 State' HphHigh; " +
                    "tinymix 'Es9018 HEADSET TYPE' 2; " +
                    "tinymix 'Es9218 Bypass' Off; " +
                    "tinymix 'HiFi Filter' On"
                );
            } else {
                // Revert to normal automatic state
                runSuCommand(
                    "tinymix 'Es9018 State' Hifi; " +
                    "tinymix 'Es9218 Bypass' Off; " +
                    "tinymix 'HiFi Filter' On"
                );
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean setEssFilter(int filterIndex) {
        if (filterIndex < 0 || filterIndex > 12) return false;
        try {
            runSuCommand("tinymix 'HIFI Custom Filter' " + filterIndex);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static int parseFirstInt(String line) {
        try {
            String[] parts = line.split(":");
            if (parts.length > 1) {
                String valPart = parts[1].trim().split(" ")[0].trim();
                return Integer.parseInt(valPart);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static String runSuCommand(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sb.toString();
    }
}
