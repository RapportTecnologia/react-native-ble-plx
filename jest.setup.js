const nativeBlePlx = {
  getConstants: () => ({
    ScanEvent: 'scan_event',
    ReadEvent: 'read_event',
    StateChangeEvent: 'state_change_event',
    RestoreStateEvent: 'restore_state_event',
    DisconnectionEvent: 'disconnection_event',
    PeripheralCentralConnectedEvent: 'peripheral_central_connected',
    PeripheralCentralDisconnectedEvent: 'peripheral_central_disconnected',
    PeripheralWriteEvent: 'peripheral_write',
    PeripheralMtuChangedEvent: 'peripheral_mtu_changed',
    PeripheralSubscriptionChangedEvent: 'peripheral_subscription_changed',
    PeripheralErrorEvent: 'peripheral_error'
  })
}

Object.defineProperty(nativeBlePlx, 'createClient', {
  configurable: true,
  enumerable: false,
  value: jest.fn()
})

jest.mock('./src/NativeBlePlx', () => ({
  __esModule: true,
  default: nativeBlePlx
}))
