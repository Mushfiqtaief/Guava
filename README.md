# 🍈 Guava — SMS Monitor & Webhook Forwarder

**Guava** is a native Android app that monitors incoming SMS messages in real-time, extracts structured data using configurable regex patterns, and forwards it to your webhook endpoints automatically.

Built for developers and businesses that need to capture transactional SMS (mobile payments, OTPs, alerts) and pipe the data into their own backend — no cloud dependency, everything runs on-device.

---

## Features

- **Real-time SMS monitoring** — Runs as a foreground service, survives reboots and battery optimization
- **Configurable patterns** — Define sender filters, body keywords, and regex extraction fields
- **Pattern Types** — Organize patterns into categories (Payment, OTP, Alert) for routing control
- **Multiple webhooks** — Send to different endpoints with independent configs per webhook
- **Type-based routing** — Subscribe each webhook to specific pattern types (e.g. OTP webhook ≠ payment webhook)
- **Flexible authentication** — API Key, Bearer Token, Custom Header, or HMAC Signature
- **Body templates** — Customize webhook payload with `{placeholders}`
- **Quick Add Pattern** — Paste a sample SMS, select text, auto-generate regex — no regex knowledge needed
- **Live request preview** — See the exact HTTP request before saving a webhook
- **Dual SIM support** — Filter patterns by SIM slot
- **Material 3 dark theme** — Clean, modern UI
- **Deduplication** — Prevents sending the same SMS data twice
- **Retry logic** — Auto-retries failed webhook calls up to 3 times
- **Debug logging** — File-based logs for troubleshooting on devices with restricted logcat

---

## Screenshots

| Dashboard | Webhooks | Settings |
|-----------|----------|----------|
| Service status, stats, payment history | Add/edit webhooks with live preview | Pattern types, SMS patterns, Quick Add |

---

## Requirements

- Android 8.0+ (API 26)
- SMS permission granted
- Internet access for webhooks

---

## Getting Started

### 1. Install the app

Build from source (see [Build](#build-from-source)) or install the APK directly.

### 2. Grant permissions

On first launch, Guava asks for:
- **SMS** — to read incoming messages
- **Notifications** — to show the foreground service indicator

### 3. Create Pattern Types

Go to **Settings → Pattern Types** and create categories:
- `Payment` (pre-seeded)
- `OTP` (pre-seeded)
- Add your own: `Alert`, `Bank`, etc.

### 4. Add SMS Patterns

Each pattern tells Guava *which* SMS messages to capture and *what* data to extract.

**Manual method:**

1. Go to **Settings → SMS Patterns → + Add**
2. Enter:
   - **Pattern Name** — e.g. `bKash` (becomes the `method` field in payloads)
   - **Pattern Type** — select `Payment`
   - **Sender** — e.g. `bkash` (case-insensitive partial match)
   - **Regex Fields** — name + regex for each value to extract

**Quick Add (no regex knowledge needed):**

1. Tap **Quick Add Pattern**
2. Enter a name, select a type, optionally set sender
3. Paste a real sample SMS
4. **Select/highlight** any value (amount, transaction ID, etc.)
5. Tap **Add Field from Selection** — regex is auto-generated
6. Repeat for each field
7. Tap **Test**, then **Save**

### 5. Add Webhooks

Go to **Webhooks → + Add**:
- Set your endpoint URL
- Choose HTTP method, content type, authentication
- Subscribe to Pattern Types (e.g. only `Payment`)
- Optionally add a body template or custom headers
- Check the **Request Preview** at the bottom

### 6. Start monitoring

Go to **Dashboard** and tap **Start Service**. Guava will now:
1. Listen for all incoming SMS
2. Match against your enabled patterns
3. Extract data using regex fields
4. Forward to matching webhooks

---

## How It Works

```
SMS Received
    │
    ▼
┌─────────────────┐
│  Sender Filter   │──── no match ──→ skip
└────────┬────────┘
         │ match
         ▼
┌─────────────────┐
│  Body Keyword    │──── no match ──→ skip
└────────┬────────┘
         │ match
         ▼
┌─────────────────┐
│  Regex Fields    │──── extract amount, trxid, etc.
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Pattern Type    │──── "Payment"
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Webhook Router  │──── match type + SIM filter
└────────┬────────┘
         │
         ▼
    HTTP POST/GET/PUT → your server
```

---

## Default Webhook Payload

When no body template is set, Guava sends:

```http
POST https://your-url.com/hook
Content-Type: application/json
X-API-Key: your-key

{
  "payments": [
    {
      "method": "bkash",
      "amount": 1500.0,
      "trxid": "ABC1234567"
    }
  ],
  "timestamp": "2026-02-21T12:00:00"
}
```

Extra fields from regex extraction are included alongside `method`, `amount`, and `trxid`.

### Custom Body Template

Use `{placeholders}` to fully customize the payload:

```json
{
  "source": "guava",
  "type": "{method}",
  "data": {
    "amount": "{amount}",
    "reference": "{trxid}",
    "raw_sms": "{sms_body}",
    "otp_code": "{otp}"
  },
  "received_at": "{timestamp}"
}
```

Available placeholders:
| Placeholder | Description |
|-------------|-------------|
| `{method}` | Pattern name (lowercase) |
| `{amount}` | Extracted amount |
| `{trxid}` | Transaction/reference ID |
| `{sms_body}` | Full SMS text |
| `{timestamp}` | ISO 8601 timestamp |
| `{sim}` | SIM slot number |
| `{sender}` | SMS sender address |
| `{yourfield}` | Any custom regex field name |

---

## Authentication Options

| Mode | Headers Sent |
|------|-------------|
| **API Key** | `X-API-Key: <key>` (header name configurable) |
| **Bearer** | `Authorization: Bearer <token>` |
| **Custom Header** | `<YourHeader>: <value>` |
| **None** | No auth headers |

### HMAC Signature Verification

When **Include Signature** is enabled, Guava appends:

```
X-Timestamp: 1740100000
X-Signature: <sha256_hex>
```

Signature = `SHA256(request_body + timestamp + secret_key)`

The signing key is the **Secret** field (falls back to API Key if empty).

**Server-side verification (PHP example):**

```php
$rawData   = file_get_contents('php://input');
$apiKey    = $_SERVER['HTTP_X_API_KEY'] ?? '';
$timestamp = $_SERVER['HTTP_X_TIMESTAMP'] ?? '';
$signature = $_SERVER['HTTP_X_SIGNATURE'] ?? '';

// Check timestamp freshness (5 min tolerance)
if (abs(time() - intval($timestamp)) > 300) {
    http_response_code(401);
    exit;
}

// Verify signature
$expected = hash('sha256', $rawData . $timestamp . API_KEY);
if (!hash_equals($expected, $signature)) {
    http_response_code(401);
    exit;
}
```

---

## Server-Side Webhook Receiver

A sample PHP webhook receiver is included at [`hook.php`](hook.php). It supports:
- API Key, Bearer, and Signature auth modes
- Duplicate transaction detection
- MySQL storage via a `Database` class

> See the file for the full implementation. Adapt the `Database` class to your own setup.

### Expected Response

Guava considers a webhook successful when:
- HTTP 200 response
- JSON body contains `"status": "success"` or `"saved" > 0` or `"duplicates" > 0`

```json
{
  "status": "success",
  "saved": 1,
  "duplicates": 0,
  "errors": 0,
  "message": "1 new payment(s) saved"
}
```

---

## Built-in Patterns

Guava ships with two default patterns (editable):

| Pattern | Sender | Fields |
|---------|--------|--------|
| **bKash** | `bkash` | `amount`: `(?i)received\s+Tk\s+([\d,]+\.?\d*)` <br> `trxid`: `(?i)TrxID\s+([A-Z0-9]{10})` |
| **Nagad** | `nagad` | `amount`: `(?i)Amount:\s*Tk\s*([\d,]+\.?\d*)` <br> `trxid`: `(?i)TxnID:\s*([A-Z0-9]+)` |

Both are linked to the `Payment` pattern type.

---

## Project Structure

```
app/src/main/java/com/smsmonitor/app/
├── MainActivity.kt              # Tab host (Dashboard, Webhooks, Settings)
├── DashboardFragment.kt         # Service control, stats, payment list
├── WebhooksFragment.kt          # Webhook CRUD with live preview
├── SettingsFragment.kt          # Pattern types, SMS patterns, docs
├── QuickAddPatternActivity.kt   # Select-text-to-regex pattern builder
│
├── SmsReceiver.kt               # BroadcastReceiver for SMS_RECEIVED
├── SmsMonitorService.kt         # Foreground service (keeps running)
├── SmsWorker.kt                 # WorkManager fallback for reliability
├── BootReceiver.kt              # Restarts service after reboot
├── RestartReceiver.kt           # Restarts service when killed
├── OemHelper.kt                 # OEM-specific battery optimization hints
│
├── SmsParser.kt                 # Pattern matching + data extraction
├── SmsPatternConfig.kt          # Pattern data model + serialization
├── PatternStore.kt              # SharedPreferences CRUD for patterns
├── PatternTypeStore.kt          # CRUD for user-defined pattern types
├── RegexHelper.kt               # Auto-regex generation from text selection
│
├── WebhookSender.kt             # HTTP client (OkHttp), auth, signature, retry
├── WebhookConfig.kt             # Webhook data model + serialization
├── WebhookStore.kt              # SharedPreferences CRUD for webhooks
├── WebhookAdapter.kt            # RecyclerView adapter for webhook list
│
├── PaymentDatabase.kt           # SQLite for local payment history
├── PaymentAdapter.kt            # RecyclerView adapter for payments
└── DebugLog.kt                  # File-based logging (for restricted devices)
```

---

## Build from Source

### Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or later
- **JDK 17**
- **Android SDK 35** (compileSdk)
- **Gradle 8.2** (included via wrapper)

### Steps

```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/Guava.git
cd Guava

# Build debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Install via ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |
| UI | Material 3 (Material Design Components) |
| Theme | Dark (`Theme.Material3.Dark.NoActionBar`) |
| HTTP | OkHttp 4.12 |
| Background | Foreground Service + WorkManager + BroadcastReceiver |
| Storage | SharedPreferences (config) + SQLite (payment history) |
| Coroutines | kotlinx-coroutines-android 1.7.3 |

---

## Permissions

| Permission | Purpose |
|-----------|---------|
| `RECEIVE_SMS` | Listen for incoming SMS |
| `READ_SMS` | Read SMS content |
| `INTERNET` | Send webhook HTTP requests |
| `ACCESS_NETWORK_STATE` | Check connectivity before sending |
| `FOREGROUND_SERVICE` | Keep monitoring alive in background |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ foreground service type |
| `POST_NOTIFICATIONS` | Show service notification |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after device reboot |
| `WAKE_LOCK` | Prevent CPU sleep during processing |

---

## Troubleshooting

### Service keeps stopping
- Disable **battery optimization** for Guava in Android settings
- Enable **auto-start** permission (Xiaomi/OPPO/Vivo/Realme)
- Lock the app in recents (swipe down on the app card)
- The app shows OEM-specific instructions on the Dashboard

### Webhook returns 401
- Verify API key matches between app and server
- If using signature, ensure the Secret field matches
- Check server clock isn't off by more than 5 minutes

### Pattern not matching
- Use the **Test** button in the pattern dialog with a real SMS
- Check sender filter is correct (case-insensitive partial match)
- Ensure regex has a **capture group**: `(\d+)` not just `\d+`
- Try **Quick Add Pattern** for auto-regex

### Debug logs
Enable **Debug Logging** in Settings. Logs write to:
```
/storage/emulated/0/Documents/SmsMonitor/debug.log
```

---

## Contributing

1. Fork the repo
2. Create your feature branch: `git checkout -b feature/my-feature`
3. Commit changes: `git commit -m 'Add my feature'`
4. Push: `git push origin feature/my-feature`
5. Open a Pull Request

---

## License

This project is open source. See [LICENSE](LICENSE) for details.

---

<p align="center">
  <b>Guava</b> — Monitor SMS. Extract data. Forward anywhere.<br>
  Built with Kotlin & Material 3
</p>
