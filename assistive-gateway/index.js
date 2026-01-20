const express = require("express");
const admin = require("firebase-admin");
const cors = require("cors");

const serviceAccount = require("./serviceAccountKey.json");

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: "https://assistive-device-alert-system-default-rtdb.firebaseio.com"
});

const db = admin.database();

const app = express();
app.use(cors());
app.use(express.json());

app.post("/alert", async (req, res) => {
  const { type, deviceId } = req.body;

  if (!type) {
    return res.status(400).send("Missing alert type");
  }

  const ref = await db.ref("alerts").push({
    type,
    deviceId: deviceId || "wearable_01",
    acknowledged: false,
    timestamp: Date.now()
  });

  console.log("🚨 Alert received:", type);

  // 🔑 IMPORTANT PART
  res.json({
    alertKey: ref.key
  });
});

app.listen(3000, () => {
  console.log("🚀 Node gateway running on port 3000");
});
