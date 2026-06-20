(ns prophet.util
  "Small pure helpers: ULID minting, content hashing, slugs."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest SecureRandom]))

;; --- hashing ---------------------------------------------------------------

(defn sha256
  "Hex sha256 of a string, prefixed `sha256:` to match the data contract."
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s "UTF-8"))]
    (str "sha256:" (apply str (map #(format "%02x" %) bs)))))

;; --- ULID ------------------------------------------------------------------
;; Crockford base32, 48-bit millisecond timestamp + 80-bit randomness.
;; Stable forever once minted; reuse is handled at the store layer by keying on
;; an entity's natural source key, never by re-minting.

(def ^:private crockford "0123456789ABCDEFGHJKMNPQRSTVWXYZ")
(def ^:private ^SecureRandom rng (SecureRandom.))

(defn- encode-base32
  "Encode a non-negative BigInteger into `n` Crockford base32 chars."
  [^java.math.BigInteger value n]
  (let [b32 (java.math.BigInteger/valueOf 32)]
    (loop [v value, acc '()]
      (if (= (count acc) n)
        (apply str acc)
        (let [[q r] [(.divide v b32) (.mod v b32)]]
          (recur q (conj acc (.charAt crockford (.intValue r)))))))))

(defn ulid
  "Mint a new ULID. Optionally pass an explicit epoch-millis (for deterministic
   tests); defaults to now."
  ([] (ulid (System/currentTimeMillis)))
  ([^long ts-ms]
   (let [time-part (encode-base32 (java.math.BigInteger/valueOf ts-ms) 10)
         rand-bits (java.math.BigInteger. 80 rng)
         rand-part (encode-base32 rand-bits 16)]
     (str time-part rand-part))))

(defn stable-ulid
  "Deterministic ULID-shaped id derived from a stable natural key (source_key).
   Same key -> same id forever, so regenerating the store reproduces every id
   (invariant #5). 128 bits of sha256(key) encoded as 26 Crockford base32 chars.
   Unlike `ulid` it carries no timestamp; ids are opaque join keys and recency is
   tracked by the `updated` field (whats_new), never by id order."
  [k]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str k) "UTF-8"))
        n  (java.math.BigInteger. 1 (java.util.Arrays/copyOf bs 16))]
    (encode-base32 n 26)))

;; --- slug ------------------------------------------------------------------

(defn slug
  "Lowercase ascii-ish slug for filenames. Keeps it short and filesystem-safe."
  [s]
  (-> (str s)
      (.toLowerCase)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")
      (->> (take 60) (apply str))))
