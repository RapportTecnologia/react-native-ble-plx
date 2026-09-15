package com.bleplx.peripheral;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
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
import android.os.Build;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bleplx.utils.Base64Converter;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BLE Peripheral (GATT server + advertiser) used by react-native-ble-mesh to let
 * devices discover and connect to each other without a central relay.
 *
 * The server exposes a single primary service with two characteristics:
 * - TX (write / write-without-response): remote centrals write data here.
 * - RX (notify): the local peripheral sends data to subscribed centrals here.
 *
 * Events are emitted to JavaScript via the supplied {@link EventListener}.
 */
public class PeripheralServer {

  private static final String CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb";

  public interface EventListener {
    void onEvent(@NonNull String eventName, @NonNull WritableMap payload);
  }

  private final ReactApplicationContext reactContext;
  private final EventListener eventListener;

  private BluetoothAdapter bluetoothAdapter;
  private BluetoothGattServer gattServer;
  private BluetoothLeAdvertiser advertiser;
  private BluetoothGattService gattService;
  private BluetoothGattCharacteristic txCharacteristic;
  private BluetoothGattCharacteristic rxCharacteristic;
  private AdvertiseCallback advertiseCallback;
  private Promise startPromise;
  private int advertiseModeValue = AdvertiseSettings.ADVERTISE_MODE_BALANCED;

  private final Map<String, BluetoothDevice> connectedCentrals = new HashMap<>();
  private final Map<String, Integer> mtuMap = new HashMap<>();
  private final Map<String, Boolean> subscribedMap = new HashMap<>();
  private final Object lock = new Object();

  public PeripheralServer(@NonNull ReactApplicationContext reactContext, @NonNull EventListener eventListener) {
    this.reactContext = reactContext;
    this.eventListener = eventListener;
  }

  public void start(@Nullable ReadableMap config, @NonNull Promise promise) {
    if (gattServer != null) {
      promise.reject("PERIPHERAL_ALREADY_STARTED", "Peripheral server is already running");
      return;
    }

    if (config == null) {
      promise.reject("INVALID_CONFIG", "Peripheral config is required");
      return;
    }

    String serviceUuid = config.hasKey("serviceUuid") ? config.getString("serviceUuid") : null;
    String txCharUuid = config.hasKey("txCharUuid") ? config.getString("txCharUuid") : null;
    String rxCharUuid = config.hasKey("rxCharUuid") ? config.getString("rxCharUuid") : null;
    String advertiseMode = config.hasKey("advertiseMode") ? config.getString("advertiseMode") : "balanced";

    if (serviceUuid == null || txCharUuid == null || rxCharUuid == null) {
      promise.reject("INVALID_CONFIG", "serviceUuid, txCharUuid and rxCharUuid are required");
      return;
    }

    this.advertiseModeValue = parseAdvertiseMode(advertiseMode);

    BluetoothManager bluetoothManager = (BluetoothManager) reactContext.getSystemService(Context.BLUETOOTH_SERVICE);
    if (bluetoothManager == null) {
      promise.reject("BLUETOOTH_UNSUPPORTED", "BluetoothManager is not available");
      return;
    }

    bluetoothAdapter = bluetoothManager.getAdapter();
    if (bluetoothAdapter == null) {
      promise.reject("BLUETOOTH_UNSUPPORTED", "Bluetooth adapter is not available");
      return;
    }

    advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
    if (advertiser == null) {
      promise.reject("ADVERTISING_UNSUPPORTED", "Bluetooth LE advertising is not supported on this device");
      return;
    }

    UUID serviceUUID = UUID.fromString(serviceUuid);
    UUID txUUID = UUID.fromString(txCharUuid);
    UUID rxUUID = UUID.fromString(rxCharUuid);

    txCharacteristic = new BluetoothGattCharacteristic(
      txUUID,
      BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
      BluetoothGattCharacteristic.PERMISSION_WRITE
    );

    rxCharacteristic = new BluetoothGattCharacteristic(
      rxUUID,
      BluetoothGattCharacteristic.PROPERTY_NOTIFY,
      BluetoothGattCharacteristic.PERMISSION_READ
    );

    BluetoothGattDescriptor cccDescriptor = new BluetoothGattDescriptor(
      UUID.fromString(CCC_DESCRIPTOR_UUID),
      BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
    );
    rxCharacteristic.addDescriptor(cccDescriptor);

    gattService = new BluetoothGattService(serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    gattService.addCharacteristic(txCharacteristic);
    gattService.addCharacteristic(rxCharacteristic);

    startPromise = promise;

    gattServer = bluetoothManager.openGattServer(reactContext, serverCallback);
    gattServer.addService(gattService);
  }

  public void stop(@Nullable Promise promise) {
    stopAdvertising();
    synchronized (lock) {
      if (gattServer != null) {
        gattServer.close();
        gattServer = null;
      }
      connectedCentrals.clear();
      subscribedMap.clear();
      mtuMap.clear();
      gattService = null;
      txCharacteristic = null;
      rxCharacteristic = null;
      bluetoothAdapter = null;
      advertiser = null;
      advertiseCallback = null;
      advertiseModeValue = AdvertiseSettings.ADVERTISE_MODE_BALANCED;
    }
    if (promise != null) {
      promise.resolve(null);
    }
  }

  public void notify(@NonNull String deviceId, @NonNull String valueBase64, @NonNull Promise promise) {
    BluetoothDevice device;
    boolean subscribed;
    synchronized (lock) {
      device = connectedCentrals.get(deviceId);
      subscribed = Boolean.TRUE.equals(subscribedMap.get(deviceId));
    }
    if (device == null) {
      promise.reject("CENTRAL_NOT_CONNECTED", "Central is not connected: " + deviceId);
      return;
    }
    if (!subscribed) {
      promise.reject("CENTRAL_NOT_SUBSCRIBED", "Central has not subscribed to RX notifications: " + deviceId);
      return;
    }
    if (gattServer == null || rxCharacteristic == null) {
      promise.reject("PERIPHERAL_NOT_STARTED", "Peripheral server is not running");
      return;
    }

    byte[] value = Base64Converter.decode(valueBase64);
    boolean success;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      success = gattServer.notifyCharacteristicChanged(device, rxCharacteristic, false, value);
    } else {
      rxCharacteristic.setValue(value);
      success = gattServer.notifyCharacteristicChanged(device, rxCharacteristic, false);
    }
    if (success) {
      promise.resolve(null);
    } else {
      promise.reject("NOTIFY_FAILED", "Failed to notify central: " + deviceId);
    }
  }

  public void cancelConnection(@NonNull String deviceId, @NonNull Promise promise) {
    BluetoothDevice device;
    synchronized (lock) {
      device = connectedCentrals.get(deviceId);
    }
    if (device != null && gattServer != null) {
      gattServer.cancelConnection(device);
    }
    promise.resolve(null);
  }

  public void connectedCentrals(@NonNull Promise promise) {
    WritableArray array = Arguments.createArray();
    synchronized (lock) {
      for (BluetoothDevice device : connectedCentrals.values()) {
        array.pushMap(deviceToMap(device));
      }
    }
    promise.resolve(array);
  }

  public void mtu(@NonNull String deviceId, @NonNull Promise promise) {
    synchronized (lock) {
      Integer value = mtuMap.get(deviceId);
      promise.resolve(value != null ? value : 23);
    }
  }

  private void stopAdvertising() {
    if (advertiser != null && advertiseCallback != null) {
      advertiser.stopAdvertising(advertiseCallback);
    }
  }

  private void startAdvertising() {
    if (advertiser == null || advertiseCallback != null || gattService == null) {
      return;
    }

    AdvertiseSettings settings = new AdvertiseSettings.Builder()
      .setAdvertiseMode(advertiseModeValue)
      .setConnectable(true)
      .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
      .setTimeout(0)
      .build();

    AdvertiseData data = new AdvertiseData.Builder()
      .addServiceUuid(new ParcelUuid(gattService.getUuid()))
      .setIncludeDeviceName(false)
      .build();

    advertiseCallback = new AdvertiseCallback() {
      @Override
      public void onStartSuccess(AdvertiseSettings settingsInEffect) {
        Promise p;
        synchronized (lock) {
          p = startPromise;
          startPromise = null;
        }
        if (p != null) {
          p.resolve(null);
        }
      }

      @Override
      public void onStartFailure(int errorCode) {
        stop();
        Promise p;
        synchronized (lock) {
          p = startPromise;
          startPromise = null;
        }
        if (p != null) {
          p.reject("ADVERTISE_START_FAILED", "Advertising start failed with error code: " + errorCode);
        }
        WritableMap payload = Arguments.createMap();
        payload.putString("message", "Advertising start failed: " + errorCode);
        emit("PeripheralError", payload);
      }
    };

    advertiser.startAdvertising(settings, data, advertiseCallback);
  }

  private WritableMap deviceToMap(@NonNull BluetoothDevice device) {
    WritableMap map = Arguments.createMap();
    map.putString("id", device.getAddress());
    String name = device.getName();
    if (name != null) {
      map.putString("name", name);
    }
    synchronized (lock) {
      Integer mtu = mtuMap.get(device.getAddress());
      map.putInt("mtu", mtu != null ? mtu : 23);
    }
    return map;
  }

  private int parseAdvertiseMode(@Nullable String mode) {
    if ("lowLatency".equals(mode)) {
      return AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY;
    }
    if ("lowPower".equals(mode)) {
      return AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
    }
    return AdvertiseSettings.ADVERTISE_MODE_BALANCED;
  }

  private void emit(@NonNull String eventName, @NonNull WritableMap payload) {
    if (eventListener != null) {
      eventListener.onEvent(eventName, payload);
    }
  }

  private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
      String deviceId = device.getAddress();
      synchronized (lock) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
          connectedCentrals.put(deviceId, device);
        } else {
          connectedCentrals.remove(deviceId);
          subscribedMap.remove(deviceId);
          mtuMap.remove(deviceId);
        }
      }
      WritableMap payload = Arguments.createMap();
      payload.putString("deviceId", deviceId);
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        emit("PeripheralCentralConnected", payload);
      } else {
        emit("PeripheralCentralDisconnected", payload);
      }
    }

    @Override
    public void onServiceAdded(int status, BluetoothGattService service) {
      Promise p;
      synchronized (lock) {
        p = startPromise;
      }
      if (status != BluetoothGatt.GATT_SUCCESS) {
        stop();
        synchronized (lock) {
          startPromise = null;
        }
        if (p != null) {
          p.reject("GATT_SERVER_ERROR", "Failed to add GATT service: " + status);
        }
        return;
      }
      startAdvertising();
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device,
                                             int requestId,
                                             BluetoothGattCharacteristic characteristic,
                                             boolean preparedWrite,
                                             boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
      String deviceId = device.getAddress();
      WritableMap payload = Arguments.createMap();
      payload.putString("deviceId", deviceId);
      if (gattService != null) {
        payload.putString("serviceUUID", gattService.getUuid().toString());
      }
      payload.putString("characteristicUUID", characteristic.getUuid().toString());
      if (value != null) {
        payload.putString("value", Base64Converter.encode(value));
      }
      emit("PeripheralWrite", payload);
      if (responseNeeded && gattServer != null) {
        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
      }
    }

    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device,
                                        int requestId,
                                        BluetoothGattDescriptor descriptor,
                                        boolean preparedWrite,
                                        boolean responseNeeded,
                                        int offset,
                                        byte[] value) {
      String deviceId = device.getAddress();
      boolean subscribed = false;
      if (value != null && value.length >= 2) {
        int bits = (value[0] & 0xFF) | ((value[1] & 0xFF) << 8);
        subscribed = (bits & 0x0001) != 0 || (bits & 0x0002) != 0;
      }
      synchronized (lock) {
        subscribedMap.put(deviceId, subscribed);
      }
      WritableMap payload = Arguments.createMap();
      payload.putString("deviceId", deviceId);
      if (descriptor.getCharacteristic() != null) {
        if (descriptor.getCharacteristic().getService() != null) {
          payload.putString("serviceUUID", descriptor.getCharacteristic().getService().getUuid().toString());
        }
        payload.putString("characteristicUUID", descriptor.getCharacteristic().getUuid().toString());
      }
      payload.putBoolean("subscribed", subscribed);
      emit("PeripheralSubscriptionChanged", payload);
      if (responseNeeded && gattServer != null) {
        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
      }
    }

    @Override
    public void onMtuChanged(BluetoothDevice device, int mtu) {
      String deviceId = device.getAddress();
      synchronized (lock) {
        mtuMap.put(deviceId, mtu);
      }
      WritableMap payload = Arguments.createMap();
      payload.putString("deviceId", deviceId);
      payload.putInt("mtu", mtu);
      emit("PeripheralMtuChanged", payload);
    }
  };
}
