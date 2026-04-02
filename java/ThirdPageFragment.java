package com.example.smartfarm;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class ThirdPageFragment extends Fragment {
    private static final String TAG = "BLE_ThirdPageFragment";

    private final static UUID SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private final static UUID CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private static final String PREFS_NAME = "BleConnectPrefs";
    private static final String LAST_DEVICE_ADDRESS_KEY = "lastDeviceAddress";

    private WebView mWebView;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mDataCharacteristic;

    private final StringBuilder mDataBuffer = new StringBuilder();

    private boolean mScanning = false;
    private Map<String, BluetoothDevice> mScanResults;
    private final Handler mScanHandler = new Handler(Looper.getMainLooper());
    private List<BluetoothDevice> mDeviceListForUI;

    private final static long SCAN_PERIOD = 10000;
    private final static long UPDATE_PERIOD = 1000;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private String lastSoilMoisture = "30";
    private String lastLight = "500";
    private String lastManualPumpOverride = "0";
    private String lastManualLedOverride = "0";
    private String lastSystemManualMode = "0";

    private final Handler mUpdateHandler = new Handler(Looper.getMainLooper());
    private final Runnable mUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (mBluetoothGatt != null && mBluetoothAdapter.isEnabled()) {
            }
            mUpdateHandler.postDelayed(this, UPDATE_PERIOD);
        }
    };

    private final ActivityResultLauncher<Intent> startBluetoothIntent = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == requireActivity().RESULT_OK) {
                    updateStatusOnWeb("활성화됨");
                    attemptAutoReconnect();
                } else {
                    updateStatusOnWeb("비활성화");
                }
            });

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissionsMap -> {
                boolean allGranted = true;
                for (Boolean isGranted : permissionsMap.values()) {
                    if (!isGranted) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    bluetoothOnCheckAndAutoReconnect();
                }
            });


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        mWebView = new WebView(requireContext());
        mWebView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final BluetoothManager mBluetoothManager = (BluetoothManager) requireContext().getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        mScanResults = new HashMap<>();
        mDeviceListForUI = new ArrayList<>();

        if (mBluetoothAdapter == null) {
            updateStatusOnWeb("블루투스 미지원");
        } else {
            updateStatusOnWeb(mBluetoothAdapter.isEnabled() ? "활성화됨" : "비활성화");
        }

        setupWebView();

        mWebView.loadUrl("file:///android_asset/ble_control.html");

        return mWebView;
    }

    private void setupWebView() {
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        mWebView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
    }

    @Override
    public void onDestroyView() {
        mUpdateHandler.removeCallbacks(mUpdateRunnable);
        mScanHandler.removeCallbacksAndMessages(null);
        if (mWebView != null) {
            mWebView.loadUrl("about:blank");
            mWebView.clearHistory();
            mWebView.destroy();
            mWebView = null;
        }
        disconnectGatt();
        super.onDestroyView();
    }

    private class AndroidBridge {

        @JavascriptInterface
        public void startBleScan(boolean isManualCall) {
            mMainHandler.post(() -> checkBluetoothPermissionsAndStartScan(isManualCall));
        }

        @JavascriptInterface
        public void disconnectGatt() {
            mMainHandler.post(() -> ThirdPageFragment.this.disconnectGatt());
        }

        @JavascriptInterface
        public void connectToDevice(String address) {
            mMainHandler.post(() -> {
                try {
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                    if (device != null) {
                        ThirdPageFragment.this.connectToDevice(device);
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid Bluetooth address received from web: " + address);
                }
            });
        }

        @JavascriptInterface
        public void sendTextCommand(String message) {
            mMainHandler.post(() -> ThirdPageFragment.this.sendTextCommand(message));
        }
    }

    private void updateStatusOnWeb(String status) {
        if (mWebView != null) {
            final String js = "javascript:updateStatus('" + status + "')";
            mMainHandler.post(() -> mWebView.evaluateJavascript(js, null));
        }
    }

    private void updateDeviceListOnWeb() {
        if (mWebView != null) {
            List<Map<String, String>> deviceMapList = new ArrayList<>();
            for (BluetoothDevice device : mDeviceListForUI) {
                Map<String, String> map = new HashMap<>();
                map.put("name", device.getName() != null ? device.getName() : "Unknown");
                map.put("address", device.getAddress());
                deviceMapList.add(map);
            }
            String json = new Gson().toJson(deviceMapList);
            final String js = "javascript:updateDeviceList('" + json + "')";

            mMainHandler.post(() -> mWebView.evaluateJavascript(js, null));
        }
    }

    private void updateReceivedDataOnWeb(String rawData) {
        if (mWebView != null) {
            String escapedRawData = rawData.replace("'", "\\'");
            final String js = "javascript:updateReceivedData('" + escapedRawData + "')";
            mMainHandler.post(() -> mWebView.evaluateJavascript(js, null));
        }
    }

    private void checkBluetoothPermissionsAndStartScan(boolean isManualCall) {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            requestPermissionsLauncher.launch(permissions);
        } else {
            if (isManualCall) {
                bluetoothOnCheckAndStartScan();
            } else {
                bluetoothOnCheckAndAutoReconnect();
            }
        }
    }

    private void bluetoothOnCheckAndStartScan() {
        if (mBluetoothAdapter.isEnabled()) {
            startScan();
        } else {
            Intent intentBluetoothEnable = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startBluetoothIntent.launch(intentBluetoothEnable);
        }
    }

    private void bluetoothOnCheckAndAutoReconnect() {
        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
            attemptAutoReconnect();
        } else if (mBluetoothAdapter != null) {
            Intent intentBluetoothEnable = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startBluetoothIntent.launch(intentBluetoothEnable);
        }
    }

    private void attemptAutoReconnect() {
        String lastAddress = loadLastDeviceAddress();

        if (!lastAddress.isEmpty() && mBluetoothAdapter.isEnabled()) {
            try {
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(lastAddress);
                if (device != null) {
                    updateStatusOnWeb("자동 재연결 시도 중...");
                    mDeviceListForUI.clear();
                    updateDeviceListOnWeb();

                    connectToDevice(device);
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid Bluetooth address: " + lastAddress);
                updateStatusOnWeb("저장된 주소 오류");
                saveLastDeviceAddress("");
            }
        } else if(loadLastDeviceAddress().isEmpty()) {
            updateStatusOnWeb("연결 기록 없음");
        }
    }

    private void disconnectGatt() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
        }
        mBluetoothGatt = null;
        mDataCharacteristic = null;
        mDataBuffer.setLength(0);

        mUpdateHandler.removeCallbacks(mUpdateRunnable);

        saveLastDeviceAddress("");

        mMainHandler.post(() -> {
            updateStatusOnWeb("연결 해제됨");
            mDeviceListForUI.clear();
            updateDeviceListOnWeb();
        });
    }

    private void stopScan() {
        if (mScanning && mBluetoothLeScanner != null) {
            mScanning = false;
            mScanHandler.removeCallbacksAndMessages(null);
            mBluetoothLeScanner.stopScan(mScanCallback);
            mMainHandler.post(() -> updateStatusOnWeb("스캔 완료"));
        }
    }

    private void startScan() {
        if (!mBluetoothAdapter.isEnabled()) return;

        if(mScanning && mBluetoothLeScanner != null) mBluetoothLeScanner.stopScan(mScanCallback);

        mScanHandler.removeCallbacksAndMessages(null);

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        mScanResults.clear();
        mDeviceListForUI.clear();
        updateDeviceListOnWeb();

        updateStatusOnWeb("스캔 중...");

        mScanHandler.postDelayed(() -> {
            if (mScanning && mBluetoothLeScanner != null) {
                mScanning = false;
                mBluetoothLeScanner.stopScan(mScanCallback);
                mMainHandler.post(() -> updateStatusOnWeb("스캔 완료"));
            }
        }, SCAN_PERIOD);

        mScanning = true;
        mBluetoothLeScanner.startScan(mScanCallback);
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            final BluetoothDevice device = result.getDevice();
            final String deviceName = device.getName();
            final String deviceAddress = device.getAddress();

            if (deviceName != null && !mScanResults.containsKey(deviceAddress)) {
                mScanResults.put(deviceAddress, device);
                mDeviceListForUI.add(device);

                mMainHandler.post(() -> updateDeviceListOnWeb());
                Log.d(TAG, "Device Found: " + deviceName + " (" + deviceAddress + ")");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            updateStatusOnWeb("스캔 실패");
            mScanning = false;
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        if (mBluetoothGatt != null) mBluetoothGatt.close();

        mBluetoothGatt = device.connectGatt(requireContext(), false, mGattCallback);
        updateStatusOnWeb("연결 시도 중: " + (device.getName() != null ? device.getName() : "Unknown"));

        mDeviceListForUI.clear();
        updateDeviceListOnWeb();

        saveLastDeviceAddress(device.getAddress());
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            mMainHandler.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    updateStatusOnWeb("연결됨. MTU 요청 중...");
                    boolean mtuSuccess = gatt.requestMtu(512);
                    Log.d(TAG, "MTU request sent: " + mtuSuccess);

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    updateStatusOnWeb("연결 안됨");
                    disconnectGatt();

                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    updateStatusOnWeb("연결 오류: " + status);
                    disconnectGatt();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed successfully: " + mtu + " bytes");
            } else {
                Log.e(TAG, "MTU change failed. Proceeding with default MTU. Status: " + status);
            }
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    mDataCharacteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
                    if (mDataCharacteristic != null) {

                        gatt.setCharacteristicNotification(mDataCharacteristic, true);

                        mMainHandler.post(() -> {
                            updateStatusOnWeb("BLE 통신 준비 완료. 데이터 수신 중");
                            mWebView.evaluateJavascript("javascript:updateDeviceList('[{\"name\":\"통신 준비 완료. 데이터 수신 중\",\"address\":\"\"}]')", null);

                            mUpdateHandler.removeCallbacks(mUpdateRunnable);
                            mUpdateHandler.post(mUpdateRunnable);
                        });
                    } else {
                        Log.e(TAG, "Characteristic not found: " + CHARACTERISTIC_UUID);
                        disconnectGatt();
                    }
                } else {
                    Log.e(TAG, "Service not found: " + SERVICE_UUID);
                    disconnectGatt();
                }
            } else {
                Log.e(TAG, "Service Discovery failed with status: " + status);
                disconnectGatt();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            final byte[] data = characteristic.getValue();
            final String rawSegment = new String(data, StandardCharsets.UTF_8);
            mDataBuffer.append(rawSegment);

            if (mDataBuffer.toString().contains("\n")) {
                String fullData = mDataBuffer.toString();

                int lastNewline = fullData.lastIndexOf('\n');

                final String messageToParse = fullData.substring(0, lastNewline).trim();

                mDataBuffer.setLength(0);
                if (lastNewline + 1 < fullData.length()) {
                    mDataBuffer.append(fullData.substring(lastNewline + 1));
                }

                mMainHandler.post(() -> {
                    parseAndSaveData(messageToParse);
                    updateReceivedDataOnWeb(messageToParse);
                });
                Log.d(TAG, "Full Data Received & Parsed: " + messageToParse);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic write successful: " + new String(characteristic.getValue(), StandardCharsets.UTF_8).trim());
            } else {
                Log.e(TAG, "Characteristic write failed: " + status);
            }
        }
    };

    private void saveLastDeviceAddress(String address) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(LAST_DEVICE_ADDRESS_KEY, address);
        editor.apply();
        Log.d(TAG, "Saved Address: " + address);
    }

    private String loadLastDeviceAddress() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String address = prefs.getString(LAST_DEVICE_ADDRESS_KEY, "");
        Log.d(TAG, "Loaded Address: " + address);
        return address;
    }

    private void parseAndSaveData(String rawData) {
        String cleanData = rawData.replace("%", "").trim();

        if (cleanData.isEmpty() || !cleanData.contains(",")) {
            Log.e(TAG, "Data too short or invalid format: " + rawData);
            return;
        }

        try {
            String[] parts = cleanData.split(",");

            if (parts.length % 2 != 0) {
                Log.e(TAG, "Data length mismatch (odd number of elements): " + rawData);
                return;
            }

            Map<String, String> dataMap = new HashMap<>();

            for (int i = 0; i < parts.length; i += 2) {
                dataMap.put(parts[i].trim(), parts[i+1].trim());
            }

            if (dataMap.containsKey("H")) {
                lastSoilMoisture = dataMap.get("H");
            }
            if (dataMap.containsKey("L")) {
                lastLight = dataMap.get("L");
            }
            if (dataMap.containsKey("MP")) {
                lastManualPumpOverride = dataMap.get("MP");
            }
            if (dataMap.containsKey("ML")) {
                lastManualLedOverride = dataMap.get("ML");
            }
            if (dataMap.containsKey("SM")) {
                lastSystemManualMode = dataMap.get("SM");
            }

            Log.d(TAG, "Data parsed: H=" + lastSoilMoisture + ", L=" + lastLight +
                    ", MP=" + lastManualPumpOverride + ", ML=" + lastManualLedOverride +
                    ", SM=" + lastSystemManualMode);

        }catch (Exception e) {
            Log.e(TAG, "Data parsing failed: " + e.getMessage());
            Log.e(TAG, "Failed raw data: " + rawData);
        }
    }

    private void sendTextCommand(String textToSend) {
        if (mBluetoothGatt == null || mDataCharacteristic == null) {
            Log.e(TAG, "BLE not connected or characteristic unavailable.");
            return;
        }

        if (textToSend.trim().isEmpty()) {
            return;
        }

        String command = textToSend + "\n";

        Log.d(TAG, "Sending Custom Text: " + command.trim());

        mDataCharacteristic.setValue(command.getBytes(StandardCharsets.UTF_8));
        mDataCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

        boolean success = mBluetoothGatt.writeCharacteristic(mDataCharacteristic);

        if (success) {
        } else {
            Log.e(TAG, "Failed to write characteristic (Custom Text).");
        }
    }
}