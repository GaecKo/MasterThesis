const axios = require("axios");

const APISIX_URL = process.env.APISIX_URL || "http://10.15.146.23:9080/api/v1/data";
const DEVICE_ID = process.env.DEVICE_ID || 1;
const INTERVAL_MS = process.env.INTERVAL_MS || 5000;
const VALID_KEY = process.env.API_KEY || "secret-key";

function randomTemp() {
  return Math.floor(Math.random() * 10) + 20;
}

// Define 3 test cases
const TEST_CASES = [
  { name: "No API Key", headers: {} },
  { name: "Wrong API Key", headers: { apikey: "wrong-key" } },
  { name: "Correct API Key", headers: { apikey: VALID_KEY } },
];

let counter = 0;

async function sendData() {
  const payload = {
    device_id: DEVICE_ID,
    temp: randomTemp(),
  };

  const test = TEST_CASES[counter % TEST_CASES.length]; // rotate between cases
  counter++;

  try {
    const res = await axios.post(APISIX_URL, payload, {
      headers: {
        "Content-Type": "application/json",
        ...test.headers,
      },
    });

    console.log(
      `[${new Date().toISOString()}] [${test.name}] Sent:`,
      payload,
      "→",
      res.status,
      "Response:",
      res.data
    );
  } catch (err) {
    if (err.response) {
      console.error(
        `[${new Date().toISOString()}] [${test.name}] Failed →`,
        err.response.status,
        err.response.statusText
      );
    } else {
      console.error(`[${new Date().toISOString()}] [${test.name}] Error:`, err.message);
    }
  }
}

setInterval(sendData, INTERVAL_MS);
