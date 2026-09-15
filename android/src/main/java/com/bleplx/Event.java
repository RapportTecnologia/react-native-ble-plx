package com.bleplx;

public enum Event {

  ScanEvent("ScanEvent"),
  ReadEvent("ReadEvent"),
  StateChangeEvent("StateChangeEvent"),
  RestoreStateEvent("RestoreStateEvent"),
  DisconnectionEvent("DisconnectionEvent"),
  PeripheralCentralConnectedEvent("PeripheralCentralConnected"),
  PeripheralCentralDisconnectedEvent("PeripheralCentralDisconnected"),
  PeripheralWriteEvent("PeripheralWrite"),
  PeripheralMtuChangedEvent("PeripheralMtuChanged"),
  PeripheralSubscriptionChangedEvent("PeripheralSubscriptionChanged"),
  PeripheralErrorEvent("PeripheralError");

  public String name;

  Event(String name) {
    this.name = name;
  }
}
