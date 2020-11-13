package org.traccar.model;

public class OutInfo {
    private String deciceNum;
    private String deviceQrCode;
    private String terminallnOut;
    private String batteryQuantity;
    private String deciceStatusCode;

    public String getDeciceNum() {
        return deciceNum;
    }

    public void setDeciceNum(String deciceNum) {
        this.deciceNum = deciceNum;
    }

    public String getDeviceQrCode() {
        return deviceQrCode;
    }

    public void setDeviceQrCode(String deviceQrCode) {
        this.deviceQrCode = deviceQrCode;
    }

    public String getTerminallnOut() {
        return terminallnOut;
    }

    public void setTerminallnOut(String terminallnOut) {
        this.terminallnOut = terminallnOut;
    }

    public String getBatteryQuantity() {
        return batteryQuantity;
    }

    public void setBatteryQuantity(String batteryQuantity) {
        this.batteryQuantity = batteryQuantity;
    }

    public String getDeciceStatusCode() {
        return deciceStatusCode;
    }

    public void setDeciceStatusCode(String deciceStatusCode) {
        this.deciceStatusCode = deciceStatusCode;
    }
}
