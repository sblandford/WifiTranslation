# WifiTranslation
Simultaneous audio translation over Wifi.

Created in Android Studio 3.1. Available on GooglePlay https://play.google.com/store/apps/details?id=eu.bkwsu.webcast.wifitranslation

Broadcast voice via multicast to many delegates over WiFi. Up to 10 channels are supported with relay facility, which enables a translator to simultaneously hear another channel. The AMR-WB codec is used for efficient use of network bandwidth.

Note that multicast will not work in all situations.
1) Not all Android devices support multicast
2) This application will only work when connected to WiFi, not just mobile data networks
3) All devices must be connected to the same network (WiFi SSID)
4) The network must allow multicast traffic
5) Having Bluetooth switched on can impair the WiFi performance on some devices

# Menu
* Transmit mode : Toggle between receiver (delegate) and transmitter (translator)
* Relay : Enable dual receiver/transmitter to translate from one language to another
# Settings
* Gain Booster: (automatic level control)
* Maximum Gain Boost: The maximum extra gain that will be applied to the microphone
* Peak limit hold time: How long to wait before increasing the gain
* Gain increase per second: How quickly to start increasing the gain
# Network
* Packet redundancy: Repeat each network packet n times to compensate for packet loss e.g. due to Bluetooth reducing WiFi performance. This also increases the network bandwidth used by n times.


# The WifiTranslation Hub
For better control of the WiFi Translation in a venue, check out the "WifiTranslationHub" that is designed to run on a computer on the same LAN.

Source code of WifiTranslationHub: https://github.com/sblandford/WifiTranslationHub

This adds the following functionality:

* Provide an HTML audio player for all other devices with a web browser
* Name translation channels e.g. "English", "French", "Hindi" etc.
* Manage who can transmit on each channel
* Provide transparent RTSP fall-back for devices with no multicast support
