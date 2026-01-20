#include <SPI.h>
#include <Wire.h>
#include <driver/i2s.h>
#include <ArduinoJson.h>

#include <WiFi.h>
#include <HTTPClient.h>

#include <Adafruit_GFX.h>
#include <Adafruit_GC9A01A.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_ADXL345_U.h>

/* ================= CONFIG ================= */
#define WIFI_SSID "WIFI-NAME"
#define WIFI_PASSWORD "WIFI-PASSWORD"
#define SERVER_URL "http://ADD-IP-ADDRESS-HERE/alert"
#define FIREBASE_DATABASE_URL "ADD URL HERE INSIDE QUOTES"

/* ================= DISPLAY ================= */
#define TFT_CS   5
#define TFT_DC   2
#define TFT_RST  4
Adafruit_GC9A01A tft(TFT_CS, TFT_DC, TFT_RST);

/* ================= SPI ================= */
#define SPI_SCK  18
#define SPI_MOSI 23

/* ================= I2C (ADXL345) ================= */
#define I2C_SDA 21
#define I2C_SCL 22
Adafruit_ADXL345_Unified accel = Adafruit_ADXL345_Unified(123);
bool accelAvailable = false;

/* ================= I2S (INMP441) ================= */
#define I2S_PORT I2S_NUM_0
#define PIN_BCLK 26
#define PIN_WS   25
#define PIN_DIN  33

/* ================= AUDIO ================= */
#define SAMPLE_RATE 16000
#define BUFFER_SIZE 256

/* ================= CLAP DETECTION ================= */
#define CLAP_THRESHOLD      4500
#define CLAP_COOLDOWN_MS    120
#define CLAP_WINDOW_MS      600

unsigned long lastClapTime = 0;
unsigned long clapWindowStart = 0;
int clapCount = 0;

/* ================= GESTURE (ADXL345) ================= */
#define JERK_THRESHOLD        6.0    // m/s^2 change to count as jerk; tune
#define JERK_COOLDOWN_MS      800

float lastAccelX = 0.0f;
unsigned long lastJerkTime = 0;

/* ================= ACKNOWLEDGMENT ================= */
#define ACK_CHECK_INTERVAL 2000  // Check every 2 seconds
unsigned long lastAckCheckTime = 0;
bool lastAckState = false;
String lastAlertKey = "";

/* ================= WIFI helper ================= */
void connectWiFi() {
  Serial.print("Connecting WiFi ");
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED) {
    delay(300);
    Serial.print(".");
    if (millis() - start > 10000) {
      Serial.println("\nWiFi timeout");
      return;
    }
  }
  Serial.println("\nWiFi connected: " + WiFi.localIP().toString());
}

/* ================= ALERT ================= */
void sendAlert(const char* type) {
  Serial.print("Sending alert: ");
  Serial.println(type);

  HTTPClient http;
  http.begin(SERVER_URL);
  http.addHeader("Content-Type", "application/json");
  String payload = "{\"type\":\"" + String(type) + "\",\"deviceId\":\"wearable_01\"}";
  int code = http.POST(payload);
  Serial.print("HTTP POST code: ");
  Serial.println(code);
  http.end();
  
  // Reset acknowledgment tracking when new alert is sent
  lastAckState = false;
  lastAlertKey = "";
}

/* ================= GESTURE HANDLER ================= */
void handleGesture(float ax) {
  unsigned long now = millis();
  float jerk = ax - lastAccelX;

  if (fabs(jerk) > JERK_THRESHOLD && (now - lastJerkTime) > JERK_COOLDOWN_MS) {
    lastJerkTime = now;
    if (jerk > 0) {
      Serial.println("RIGHT JERK");
      tft.fillScreen(0x0000);
      tft.setCursor(20, 110);
      tft.setTextColor(0xFFFF);
      tft.setTextSize(2);
      tft.println("RIGHT");
      sendAlert("GESTURE_RIGHT");
    } else {
      Serial.println("LEFT JERK");
      tft.fillScreen(0x0000);
      tft.setCursor(20, 110);
      tft.setTextColor(0xFFFF);
      tft.setTextSize(2);
      tft.println("LEFT");
      sendAlert("GESTURE_LEFT");
    }
  }

  lastAccelX = ax;
}

/* ================= ACKNOWLEDGMENT CHECK ================= */
bool checkAcknowledgment() {
  String url = String(FIREBASE_DATABASE_URL) + "/alerts.json?orderBy=\"deviceId\"&equalTo=\"wearable_01\"&limitToLast=1";
  HTTPClient http;
  http.begin(url);
  
  int code = http.GET();
  if (code == 200) {
    String response = http.getString();
    
    StaticJsonDocument<1024> doc;
    DeserializationError error = deserializeJson(doc, response);
    
    if (!error) {
      JsonObject obj = doc.as<JsonObject>();
      for (JsonPair kv : obj) {
        String alertKey = String(kv.key().c_str());
        JsonObject alertData = kv.value().as<JsonObject>();
        
        if (alertData.containsKey("acknowledged") && alertData["acknowledged"] == true) {
          lastAlertKey = alertKey;
          http.end();
          return true;
        }
      }
    }
  }
  http.end();
  return false;
}

/* ================= ACKNOWLEDGMENT DISPLAY ================= */
void showAcknowledgmentMessage() {
  tft.fillScreen(0x0000);
  tft.setTextColor(0x07E0);  // Green color (RGB565)
  tft.setTextSize(2);
  tft.setCursor(15, 80);
  tft.println("ON THE");
  tft.setCursor(40, 110);
  tft.println("WAY");
  Serial.println("Acknowledgment received!");
}

/* ================= SETUP ================= */
void setup() {
  Serial.begin(115200);
  delay(1200);
  Serial.println("BOOT");

  connectWiFi();

  // SPI + TFT
  SPI.begin(SPI_SCK, -1, SPI_MOSI, TFT_CS);
  Serial.println("SPI started");
  tft.begin();
  tft.fillScreen(0x0000);
  tft.setTextColor(0xFFFF);
  tft.setTextSize(2);
  tft.setCursor(30, 110);
  tft.println("READY");

  // I2C + ADXL345
  Wire.begin(I2C_SDA, I2C_SCL);
  delay(50);
  Serial.println("I2C started (SDA=" + String(I2C_SDA) + " SCL=" + String(I2C_SCL) + ")");
  if (!accel.begin()) {
    Serial.println("WARNING: ADXL345 init FAILED");
    accelAvailable = false;
  } else {
    accelAvailable = true;
    accel.setRange(ADXL345_RANGE_16_G);
    // initialize lastAccelX to current reading
    sensors_event_t evt;
    accel.getEvent(&evt);
    lastAccelX = evt.acceleration.x;
    Serial.println("ADXL345 OK, lastAccelX=" + String(lastAccelX));
  }

  // I2S init (disable MCLK)
  i2s_config_t cfg = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 4,
    .dma_buf_len = BUFFER_SIZE,
    .use_apll = false,
    .fixed_mclk = 0
  };

  i2s_pin_config_t pins = {
    .bck_io_num = PIN_BCLK,
    .ws_io_num = PIN_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = PIN_DIN
  };

  esp_err_t r = i2s_driver_install(I2S_PORT, &cfg, 0, NULL);
  if (r != ESP_OK) Serial.printf("i2s_driver_install failed: %d\n", r);
  r = i2s_set_pin(I2S_PORT, &pins);
  if (r != ESP_OK) Serial.printf("i2s_set_pin failed: %d\n", r);

  Serial.println("SYSTEM READY");
}

/* ================= LOOP ================= */
void loop() {
  unsigned long now = millis();

  // ---- MIC / CLAP ----
  static int32_t samples[BUFFER_SIZE];
  size_t bytesRead = 0;

  if (i2s_read(I2S_PORT, samples, sizeof(samples), &bytesRead, 0) == ESP_OK && bytesRead > 0) {
    int count = bytesRead / sizeof(int32_t);
    long sum = 0;
    for (int i = 0; i < count; ++i) {
      sum += abs(samples[i] >> 14);
    }
    int energy = (count > 0) ? (sum / count) : 0;

    // clap detection
    if (energy > CLAP_THRESHOLD && (now - lastClapTime) > CLAP_COOLDOWN_MS) {
      lastClapTime = now;
      if (clapCount == 0) clapWindowStart = now;
      clapCount++;
      Serial.print("CLAP ");
      Serial.println(clapCount);
    }

    // evaluate pattern
    if (clapCount > 0 && (now - clapWindowStart) > CLAP_WINDOW_MS) {
      if (clapCount == 2) {
        Serial.println("DOUBLE CLAP");
        tft.fillScreen(0x0000);
        tft.setCursor(10, 110);
        tft.setTextColor(0xFFFF);
        tft.setTextSize(2);
        tft.println("DOUBLE CLAP");
        sendAlert("DOUBLE_CLAP");
      } else if (clapCount >= 3) {
        Serial.println("TRIPLE CLAP");
        tft.fillScreen(0x0000);
        tft.setCursor(10, 110);
        tft.setTextColor(0xFFFF);
        tft.setTextSize(2);
        tft.println("TRIPLE CLAP");
        sendAlert("TRIPLE_CLAP");
      }
      clapCount = 0;
    }
  }

  // ---- ACCEL / GESTURE ----
  if (accelAvailable) {
    sensors_event_t evt;
    accel.getEvent(&evt);
    handleGesture(evt.acceleration.x);
  }

  // ---- CHECK FOR ACKNOWLEDGMENT ----
  if (now - lastAckCheckTime >= ACK_CHECK_INTERVAL) {
    lastAckCheckTime = now;
    bool isAcknowledged = checkAcknowledgment();
    
    if (isAcknowledged && !lastAckState) {
      lastAckState = true;
      showAcknowledgmentMessage();
    }
  }

  delay(10);
}