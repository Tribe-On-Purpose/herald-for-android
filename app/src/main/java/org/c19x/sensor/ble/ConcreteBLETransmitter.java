package org.c19x.sensor.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import org.c19x.sensor.PayloadDataSupplier;
import org.c19x.sensor.SensorDelegate;
import org.c19x.sensor.data.ConcreteSensorLogger;
import org.c19x.sensor.data.SensorLogger;
import org.c19x.sensor.datatype.BluetoothState;
import org.c19x.sensor.datatype.Data;
import org.c19x.sensor.datatype.PayloadData;
import org.c19x.sensor.datatype.PayloadTimestamp;
import org.c19x.sensor.datatype.Proximity;
import org.c19x.sensor.datatype.ProximityMeasurementUnit;
import org.c19x.sensor.datatype.RSSI;
import org.c19x.sensor.datatype.SensorType;
import org.c19x.sensor.datatype.TargetIdentifier;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED;
import static android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE;
import static android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED;
import static android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR;
import static android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS;

public class ConcreteBLETransmitter implements BLETransmitter, BluetoothStateManagerDelegate {
    private SensorLogger logger = new ConcreteSensorLogger("Sensor", "BLE.ConcreteBLETransmitter");
    private final ConcreteBLETransmitter self = this;
    private final Context context;
    private final BluetoothStateManager bluetoothStateManager;
    private final PayloadDataSupplier payloadDataSupplier;
    private final BLEDatabase database;
    private final Handler handler;
    private final ExecutorService operationQueue = Executors.newSingleThreadExecutor();
    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    private BluetoothGattServer bluetoothGattServer;
    private AdvertiseCallback advertiseCallback;
    private boolean enabled = false;

    /**
     * Transmitter starts automatically when Bluetooth is enabled.
     */
    public ConcreteBLETransmitter(Context context, BluetoothStateManager bluetoothStateManager, PayloadDataSupplier payloadDataSupplier, BLEDatabase database) {
        this.context = context;
        this.bluetoothStateManager = bluetoothStateManager;
        this.payloadDataSupplier = payloadDataSupplier;
        this.database = database;
        this.handler = new Handler(Looper.getMainLooper());
        bluetoothStateManager.delegates.add(this);
        bluetoothStateManager(bluetoothStateManager.state());
    }

    @Override
    public void add(SensorDelegate delegate) {
        delegates.add(delegate);
    }

    @Override
    public void start() {
        if (bluetoothLeAdvertiser == null) {
            final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter != null && bluetoothAdapter.isMultipleAdvertisementSupported()) {
                bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            }
        }
        if (bluetoothLeAdvertiser == null) {
            logger.fault("Bluetooth LE advertiser unsupported");
            return;
        }
        if (bluetoothStateManager.state() != BluetoothState.poweredOn) {
            logger.fault("Bluetooth is not powered on");
            return;
        }
        if (advertiseCallback != null) {
            logger.fault("Already started");
            return;
        }
        enabled = true;
        startAdvertising();
        logger.debug("start");
    }

    @Override
    public void stop() {
        enabled = false;
        if (advertiseCallback == null) {
            logger.fault("Already stopped");
            return;
        }
        operationQueue.execute(new Runnable() {
            @Override
            public void run() {
                bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
                advertiseCallback = null;
            }
        });
        if (bluetoothGattServer != null) {
            operationQueue.execute(new Runnable() {
                @Override
                public void run() {
                    bluetoothGattServer.clearServices();
                    bluetoothGattServer.close();
                    bluetoothGattServer = null;
                }
            });
        }
    }

    private void startAdvertising() {
        logger.debug("startAdvertising");
        if (bluetoothLeAdvertiser == null) {
            final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter != null && bluetoothAdapter.isMultipleAdvertisementSupported()) {
                bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            }
        }
        if (bluetoothLeAdvertiser == null) {
            logger.fault("Bluetooth LE advertiser unsupported");
            return;
        }
        if (bluetoothStateManager.state() != BluetoothState.poweredOn) {
            logger.fault("Bluetooth is not powered on");
            return;
        }
        if (advertiseCallback != null) {
            operationQueue.execute(new Runnable() {
                @Override
                public void run() {
                    bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
                    advertiseCallback = null;
                }
            });
        }
        if (bluetoothGattServer != null) {
            operationQueue.execute(new Runnable() {
                @Override
                public void run() {
                    bluetoothGattServer.clearServices();
                    bluetoothGattServer.close();
                    bluetoothGattServer = null;
                }
            });
        }
        if (!enabled) {
            return;
        }
        operationQueue.execute(new Runnable() {
            @Override
            public void run() {
                bluetoothGattServer = startGattServer(logger, context, payloadDataSupplier, self, database);
                setGattService(logger, context, bluetoothGattServer);
                advertiseCallback = startAdvertising(logger, bluetoothLeAdvertiser);
            }
        });
    }

    @Override
    public PayloadData payloadData() {
        return payloadDataSupplier.payload(new PayloadTimestamp(new Date()));
    }

    @Override
    public boolean isSupported() {
        return bluetoothLeAdvertiser != null;
    }

    @Override
    public void bluetoothStateManager(BluetoothState didUpdateState) {
        logger.debug("didUpdateState (state={})", didUpdateState);
        if (didUpdateState == BluetoothState.poweredOn) {
            start();
        } else if (didUpdateState == BluetoothState.poweredOff) {
            stop();
        }
    }

    private static class PayloadSharingData {
        public final List<TargetIdentifier> identifiers;
        public final Data data;

        private PayloadSharingData(List<TargetIdentifier> identifiers, Data data) {
            this.identifiers = identifiers;
            this.data = data;
        }
    }

    /// Determine what payload data to share with peer
    private PayloadSharingData payloadSharingData(BLEDevice peer) {
        // Get other devices that were seen recently by this device
        final List<BLEDevice> unknownDevices = new ArrayList<>();
        final List<BLEDevice> knownDevices = new ArrayList<>();
        for (BLEDevice device : database.devices()) {
            // Device was seen recently
            if (device.timeIntervalSinceLastUpdate().value >= BLESensorConfiguration.payloadSharingTimeInterval.value) {
                continue;
            }
            // Device has payload
            if (device.payloadData() == null) {
                continue;
            }
            // Device is iOS (Android is always discoverable)
            if (device.operatingSystem() != BLEDeviceOperatingSystem.ios) {
                continue;
            }
            // Payload is new to peer
            if (peer.payloadSharingData.contains(device.payloadData())) {
                knownDevices.add(device);
            } else {
                unknownDevices.add(device);
            }
        }
        // Most recently seen unknown devices first
        final List<BLEDevice> devices = new ArrayList<>();
        Collections.sort(unknownDevices, new Comparator<BLEDevice>() {
            @Override
            public int compare(BLEDevice d0, BLEDevice d1) {
                return Long.compare(d1.lastUpdatedAt.getTime(), d0.lastUpdatedAt.getTime());
            }
        });
        Collections.sort(knownDevices, new Comparator<BLEDevice>() {
            @Override
            public int compare(BLEDevice d0, BLEDevice d1) {
                return Long.compare(d1.lastUpdatedAt.getTime(), d0.lastUpdatedAt.getTime());
            }
        });
        devices.addAll(unknownDevices);
        devices.addAll(knownDevices);
        // Limit how much to share to avoid oversized data transfers over BLE (512 bytes limit according to spec, 510 with response, iOS requires response)
        final List<TargetIdentifier> identifiers = new ArrayList<>();
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        for (BLEDevice device : devices) {
            if (device.payloadData() == null) {
                continue;
            }
            // Sharing only one for Android devices
            if (peer.operatingSystem() == BLEDeviceOperatingSystem.android && identifiers.size() >= 1) {
                break;
            }
            if (device.payloadData().value.length + byteArrayOutputStream.toByteArray().length > (2 * 129)) {
                break;
            }
            try {
                byteArrayOutputStream.write(device.payloadData().value);
                identifiers.add(device.identifier);
                device.payloadSharingData.add(device.payloadData());
            } catch (Throwable e) {
            }
        }
        final Data data = new Data(byteArrayOutputStream.toByteArray());
        return new PayloadSharingData(identifiers, data);
    }


    private final static AdvertiseCallback startAdvertising(final SensorLogger logger, final BluetoothLeAdvertiser bluetoothLeAdvertiser) {
        final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .build();

        final AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(BLESensorConfiguration.serviceUUID))
                .build();

        final AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                logger.debug("advertising (settingsInEffect={})", settingsInEffect);
            }

            @Override
            public void onStartFailure(int errorCode) {
                logger.fault("advertising failed to start (error={})", onStartFailureErrorCodeToString(errorCode));
            }
        };
        bluetoothLeAdvertiser.startAdvertising(settings, data, callback);
        return callback;
    }

    private final static BluetoothGattServer startGattServer(final SensorLogger logger, final Context context, final PayloadDataSupplier payloadDataSupplier, final ConcreteBLETransmitter concreteBLETransmitter, final BLEDatabase database) {
        final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            logger.fault("Bluetooth unsupported");
            return null;
        }
        // Data = rssi (4 bytes int) + payload (remaining bytes)
        final AtomicReference<BluetoothGattServer> server = new AtomicReference<>(null);
        final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
            private Map<String, PayloadData> onCharacteristicReadPayloadData = new ConcurrentHashMap<>();
            private Map<String, PayloadSharingData> onCharacteristicReadPayloadSharingData = new ConcurrentHashMap<>();

            private PayloadData onCharacteristicReadPayloadData(BluetoothDevice device, int requestId) {
                final String key = device.getAddress();
                if (onCharacteristicReadPayloadData.containsKey(key)) {
                    return onCharacteristicReadPayloadData.get(key);
                }
                final PayloadData payloadData = payloadDataSupplier.payload(new PayloadTimestamp());
                onCharacteristicReadPayloadData.put(key, payloadData);
                return payloadData;
            }

            private PayloadSharingData onCharacteristicReadPayloadSharingData(BluetoothDevice device, int requestId) {
                final String key = device.getAddress();
                if (onCharacteristicReadPayloadSharingData.containsKey(key)) {
                    return onCharacteristicReadPayloadSharingData.get(key);
                }
                final BLEDevice targetDevice = database.device(device);
                final PayloadSharingData payloadSharingData = concreteBLETransmitter.payloadSharingData(targetDevice);
                onCharacteristicReadPayloadSharingData.put(key, payloadSharingData);
                return payloadSharingData;
            }

            private void removeData(BluetoothDevice device) {
                final String deviceAddress = device.getAddress();
                final List<String> deviceRequestIds = new ArrayList<>(onCharacteristicReadPayloadData.keySet());
                for (String deviceRequestId : deviceRequestIds) {
                    if (deviceRequestId.startsWith(deviceAddress)) {
                        onCharacteristicReadPayloadData.remove(deviceRequestId);
                    }
                }
            }

            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                logger.debug("onConnectionStateChange (device={},status={},newState={})",
                        device, status, onConnectionStateChangeStatusToString(newState));
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Register central
                    database.device(device);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    removeData(device);
                }
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                final BLEDevice targetDevice = database.device(device);
                final TargetIdentifier targetIdentifier = targetDevice.identifier;
                logger.debug("didReceiveWrite (central={},requestId={},offset={},characteristic={},value={})",
                        targetDevice, requestId, offset,
                        (characteristic.getUuid().equals(BLESensorConfiguration.androidSignalCharacteristicUUID) ? "signal" : "unknown"),
                        (value != null ? value.length : "null")
                );
                if (characteristic.getUuid() == BLESensorConfiguration.androidSignalCharacteristicUUID && value.length >= 4) {
                    final ByteBuffer byteBuffer = ByteBuffer.wrap(value);
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
                    final RSSI rssi = new RSSI(byteBuffer.getInt(0));
                    final byte[] payloadDataBytes = new byte[value.length - 4];
                    System.arraycopy(value, 4, payloadDataBytes, 0, payloadDataBytes.length);
                    final PayloadData payloadData = new PayloadData(payloadDataBytes);
                    final Proximity proximity = new Proximity(ProximityMeasurementUnit.RSSI, new Double(rssi.value));
                    if (responseNeeded) {
                        server.get().sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                    }
                    logger.debug("didReceiveWrite -> didDetect={}", targetIdentifier);
                    for (SensorDelegate delegate : delegates) {
                        delegate.sensor(SensorType.BLE, targetIdentifier);
                    }
                    logger.debug("didReceiveWrite -> didMeasure={},fromTarget={}", proximity.description(), targetIdentifier);
                    for (SensorDelegate delegate : delegates) {
                        delegate.sensor(SensorType.BLE, proximity, targetIdentifier);
                    }
                    logger.debug("didReceiveWrite -> didRead={},fromTarget={}", payloadData.description(), targetIdentifier);
                    for (SensorDelegate delegate : delegates) {
                        delegate.sensor(SensorType.BLE, payloadData, targetIdentifier);
                    }
                } else {
                    if (responseNeeded) {
                        server.get().sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                    }
                }
                //server.get().cancelConnection(device);
            }

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
                final BLEDevice targetDevice = database.device(device);
                final TargetIdentifier targetIdentifier = targetDevice.identifier;
                if (characteristic.getUuid() == BLESensorConfiguration.payloadCharacteristicUUID) {
                    logger.debug("didReceiveRead (central={},requestId={},offset={},characteristic=payload)", targetDevice, requestId, offset);
                    final PayloadData payloadData = onCharacteristicReadPayloadData(device, requestId);
                    if (offset > payloadData.value.length) {
                        logger.fault("didReceiveRead, invalid offset (central={},requestId={},offset={},characteristic=payload,dataLength={})", targetDevice, requestId, offset, payloadData.value.length);
                        server.get().sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                    } else {
                        final byte[] value = Arrays.copyOfRange(payloadData.value, offset, payloadData.value.length);
                        server.get().sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                        logger.debug("didReceiveRead (central={},requestId={},offset={},characteristic=payload)", targetDevice, requestId, offset);
                    }
                } else if (characteristic.getUuid() == BLESensorConfiguration.payloadSharingCharacteristicUUID) {
                    final PayloadSharingData payloadSharingData = onCharacteristicReadPayloadSharingData(device, requestId);
                    if (payloadSharingData.identifiers.size() == 0 || payloadSharingData.data.value.length == 0) {
                        logger.debug("didReceiveRead (central={},requestId={},offset={},characteristic=payloadSharing,shared=[])", targetDevice, requestId, offset);
                        server.get().sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                    } else if (offset > payloadSharingData.data.value.length) {
                        logger.fault("didReceiveRead, invalid offset (central={},requestId={},offset={},characteristic=payloadSharing,dataLength={})", targetDevice, requestId, offset, payloadSharingData.data.value.length);
                        server.get().sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                    } else {
                        logger.debug("didReceiveRead (central={},requestId={},offset={},characteristic=payloadSharing,shared={})", targetDevice, requestId, offset, payloadSharingData.identifiers);
                        final byte[] value = Arrays.copyOfRange(payloadSharingData.data.value, offset, payloadSharingData.data.value.length);
                        server.get().sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                    }
                } else {
                    logger.fault("didReceiveRead (central={},characteristic=unknown)", targetDevice);
                    server.get().sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null);
                }
            }
        };
        server.set(bluetoothManager.openGattServer(context, callback));
        logger.debug("GATT server started");
        return server.get();
    }

    private final static void setGattService(final SensorLogger logger, final Context context, final BluetoothGattServer bluetoothGattServer) {
        final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            logger.fault("Bluetooth unsupported");
            return;
        }
        if (bluetoothGattServer == null) {
            logger.fault("Bluetooth LE advertiser unsupported");
            return;
        }
        for (BluetoothDevice device : bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)) {
            bluetoothGattServer.cancelConnection(device);
        }
        for (BluetoothDevice device : bluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER)) {
            bluetoothGattServer.cancelConnection(device);
        }
        bluetoothGattServer.clearServices();
        final BluetoothGattService service = new BluetoothGattService(BLESensorConfiguration.serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        final BluetoothGattCharacteristic signalCharacteristic = new BluetoothGattCharacteristic(
                BLESensorConfiguration.androidSignalCharacteristicUUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        signalCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        final BluetoothGattCharacteristic payloadCharacteristic = new BluetoothGattCharacteristic(
                BLESensorConfiguration.payloadCharacteristicUUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        final BluetoothGattCharacteristic payloadSharingCharacteristic = new BluetoothGattCharacteristic(
                BLESensorConfiguration.payloadSharingCharacteristicUUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        service.addCharacteristic(signalCharacteristic);
        service.addCharacteristic(payloadCharacteristic);
        service.addCharacteristic(payloadSharingCharacteristic);
        bluetoothGattServer.addService(service);
        logger.debug("setGattService (service={},signalCharacteristic={},payloadCharacteristic={},payloadSharingCharacteristic={})",
                service.getUuid(), signalCharacteristic.getUuid(), payloadCharacteristic.getUuid(), payloadSharingCharacteristic.getUuid());
    }

    private final static String onConnectionStateChangeStatusToString(final int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                return "STATE_CONNECTED";
            case BluetoothProfile.STATE_CONNECTING:
                return "STATE_CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTING:
                return "STATE_DISCONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED:
                return "STATE_DISCONNECTED";
            default:
                return "UNKNOWN_STATE_" + state;
        }
    }

    private final static String onStartFailureErrorCodeToString(final int errorCode) {
        switch (errorCode) {
            case ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "ADVERTISE_FAILED_DATA_TOO_LARGE";
            case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS";
            case ADVERTISE_FAILED_ALREADY_STARTED:
                return "ADVERTISE_FAILED_ALREADY_STARTED";
            case ADVERTISE_FAILED_INTERNAL_ERROR:
                return "ADVERTISE_FAILED_INTERNAL_ERROR";
            case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "ADVERTISE_FAILED_FEATURE_UNSUPPORTED";
            default:
                return "UNKNOWN_ERROR_CODE_" + errorCode;
        }
    }

}
