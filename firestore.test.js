const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp() {
  return new Date(Date.now() - 2000);
}

function validProfile(uid) {
  const ts = pastTimestamp();
  return {
    userId: uid,
    displayName: "Usman",
    preferredName: "Usman",
    preferredLanguage: "ENGLISH",
    selectedVoiceId: "21m00Tcm4TlvDq8ikWAM",
    selectedVoiceGender: "FEMALE",
    speechSpeed: 1.0,
    speechVolume: 1.0,
    autoPlayVoice: true,
    memoryEnabled: true,
    cloudSyncEnabled: true,
    dailyRemindersEnabled: false,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validMemory(uid) {
  const ts = pastTimestamp();
  return {
    userId: uid,
    category: "Interest",
    content: "Loves Telugu cinema and cricket",
    createdAt: ts,
    updatedAt: ts,
  };
}

function validConversation(uid) {
  const ts = pastTimestamp();
  return {
    userId: uid,
    title: "Evening Chat",
    languageCode: "TELUGU",
    lastMessagePreview: "హేయ్! బాగున్నా!",
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write profiles or memories", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("memories").get());
});

test("Authenticated user can create, read, update, and delete own profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);
  await assertSucceeds(profileRef.set(validProfile(ALICE_UID)));
  await assertSucceeds(profileRef.get());
  await assertSucceeds(
    profileRef.update({
      preferredLanguage: "HINDI",
      updatedAt: pastTimestamp(),
    })
  );
  await assertSucceeds(profileRef.delete());
});

test("Shadow update with ghost field on user profile is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);
  await assertFails(
    profileRef.set({
      ...validProfile(ALICE_UID),
      isAdmin: true,
    })
  );
});

test("Cross-user access to profile, memories, and conversations is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("memories").doc("mem_1").set(validMemory(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1").set(validConversation(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("memories").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("conversations").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("memories").doc("mem_2").set(validMemory(BOB_UID))
  );
});

test("User can manage own memories, conversations, and messages", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const memRef = aliceDb.collection("users").doc(ALICE_UID).collection("memories").doc("mem_1");
  await assertSucceeds(memRef.set(validMemory(ALICE_UID)));
  await assertSucceeds(
    memRef.update({
      content: "Prefers morning chats in Telugu",
      updatedAt: pastTimestamp(),
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("memories").get());

  const convRef = aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1");
  await assertSucceeds(convRef.set(validConversation(ALICE_UID)));
  await assertSucceeds(
    convRef.collection("messages").doc("msg_1").set({
      userId: ALICE_UID,
      role: "user",
      text: "హాయ్ సాథీ, ఎలా ఉన్నావ్?",
      isVoice: true,
      createdAt: pastTimestamp(),
    })
  );
  await assertSucceeds(convRef.collection("messages").get());
  await assertFails(convRef.collection("messages").doc("msg_1").update({ text: "tampered" }));
});
