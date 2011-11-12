(ns carrit.named-binary-tag
  "Named Binary Tag format functions"
  (:require [clojure.contrib.logging :as logging])
  (:use carrit.byte-convert)
  (:import java.util.Arrays))

(set! *warn-on-reflection* true)

(def ^{:doc "Tag end, denoting the end of a compound tag list."} *end* 0)
(def ^{:doc "Single, signed byte."} *byte* 1)
(def ^{:doc "Signed short, 16 bits."} *short* 2)
(def ^{:doc "Signed int, 32 bits."} *int* 3)
(def ^{:doc "Signed long, 64 bits."} *long* 4)
(def ^{:doc "Float, 32 bits IEEE 754-2008."} *float* 5)
(def ^{:doc "Double, 64 bits IEEE 754-2008."} *double* 6)
(def ^{:doc "Byte array of unspecified format."} *byte-array* 7)
(def ^{:doc "String."} *string* 8)
(def ^{:doc "Sequential list of a specified type."} *list* 9)
(def ^{:doc "Compound tag, which is a sequential list of uniquely named tags."} *compound* 10)

(def ^{:doc "UTF-8 encoding"} *utf-8* "UTF-8")

(defrecord NamedBinaryTag [type name payload])

; TODO: Better way to do this?
(defn copy-from-byte-array [^bytes chunk-bytes ^Integer idx length]
  "Copies bytes from an array and returns them as a new array."
  (Arrays/copyOfRange chunk-bytes idx #^Integer (+ idx length)))

(defn read-utf-8-segment [^bytes chunk-bytes idx byte-length]
  "Reads the specified number of bytes at the given index in the array and
returns it as a UTF-8 string."
  (let [^bytes dest (copy-from-byte-array chunk-bytes idx byte-length)]
    (String. dest #^String *utf-8*)))

(defn read-utf-8 [^bytes chunk-bytes idx]
  "Read a tag name from the byte array at the specified index, returning the name and length, in bytes."
  (let [length (num-from-byte-array chunk-bytes idx *short-length*)]
    {:data (read-utf-8-segment chunk-bytes (+ idx *short-length*) length) :length length}))

; TODO: Any way to do a partial of read-fn with read-length before passing it in?
(defn read-nbt-from-byte-array
  [nbt-type chunk-bytes idx read-fn]
  (let [string-data (read-utf-8 chunk-bytes idx)]
    (NamedBinaryTag. nbt-type (string-data :data) (read-fn chunk-bytes (+ idx (string-data :length))))))

; Given a byte array, read the payload for the specified type and return that
; payload and the length, in bytes, of the section that was read.
(defmulti payload-from-byte-array (fn [type-id chunk-bytes idx] type-id))

(defn read-nbt-from-byte-array [^bytes chunk-bytes idx]
  (let [nbt-type (aget chunk-bytes idx)]
    (logging/debug (apply format "nbt type is %d, index %d" [nbt-type idx]))
    (if (= nbt-type *end*)
      {:data (NamedBinaryTag. nbt-type nil nil) :length 1}
      (let [nbt-name-data (read-utf-8 chunk-bytes (inc idx))
            ; payload index starts after type, name length and name
            payload-data (payload-from-byte-array nbt-type chunk-bytes (+ idx 1 *short-length* (nbt-name-data :length)))]
        (logging/debug nbt-name-data)
        {:data (NamedBinaryTag. nbt-type (nbt-name-data :data) (payload-data :data))
         ; tag id length + "name length" length + name length + payload length
         :length (+ 1 *short-length* (nbt-name-data :length) (payload-data :length))}))))

(defn nbt-from-byte-array [^bytes chunk-bytes idx]
  ((read-nbt-from-byte-array chunk-bytes idx) :data))

(defmethod payload-from-byte-array *byte* [tag-id ^bytes chunk-bytes idx]
  {:data (aget chunk-bytes idx) :length 1})

(defmethod payload-from-byte-array *short* [tag-id ^bytes chunk-bytes idx]
  {:data (num-from-byte-array chunk-bytes idx *short-length*) :length *short-length*})

(defmethod payload-from-byte-array *int* [tag-id ^bytes chunk-bytes idx]
  {:data (num-from-byte-array chunk-bytes idx *int-length*) :length *int-length*})

(defmethod payload-from-byte-array *long* [tag-id ^bytes chunk-bytes idx]
  {:data (num-from-byte-array chunk-bytes idx *long-length*) :length *long-length*})

(defmethod payload-from-byte-array *float* [tag-id ^bytes chunk-bytes idx]
  {:data (num-from-byte-array chunk-bytes idx *float-length*) :length *float-length*})

(defmethod payload-from-byte-array *double* [tag-id ^bytes chunk-bytes idx]
  {:data (num-from-byte-array chunk-bytes idx *double-length*) :length *double-length*})

(defmethod payload-from-byte-array *byte-array* [tag-id ^bytes chunk-bytes idx]
  (let [length (num-from-byte-array chunk-bytes idx *int-length*)]
    {:data (copy-from-byte-array chunk-bytes (+ idx *int-length*) length)
     :length (+ *int-length* length)}))

(defmethod payload-from-byte-array *string* [tag-id ^bytes chunk-bytes idx]
  (let [length (num-from-byte-array chunk-bytes idx *short-length*)]
    {:data (read-utf-8-segment chunk-bytes (+ idx *short-length*) length)
     :length (+ *short-length* length)}))

(defmethod payload-from-byte-array *list* [tag-id ^bytes chunk-bytes idx]
  (let [tag-id (aget chunk-bytes idx)
        list-length (num-from-byte-array chunk-bytes (inc idx) *int-length*)]
    (loop [num-left list-length next-idx (+ idx 1 *int-length*) acc []]
      (if (zero? num-left)
        acc
        (let [payload-data (payload-from-byte-array tag-id chunk-bytes next-idx)]
          (recur (dec num-left) (conj acc (payload-data :data)) (+ next-idx payload-data :length)))))))

(defmethod payload-from-byte-array *compound* [tag-id ^bytes chunk-bytes idx]
  (loop [nbt-meta (read-nbt-from-byte-array chunk-bytes idx) acc [] length-acc 0]
    (logging/debug nbt-meta)
    (let [nbt (nbt-meta :data) nbt-length (nbt-meta :length)] 
      (if (= (:type nbt) *end*)
        {:data (conj acc nbt) :length (+ length-acc nbt-length)}
        (recur (read-nbt-from-byte-array chunk-bytes (+ idx length-acc nbt-length))
               (conj acc nbt)
               (+ length-acc nbt-length))))))

(defn as-nbt [^bytes chunk-bytes]
  (let [nbt-type (aget chunk-bytes 0)]
    (if (= nbt-type *compound*)
      (NamedBinaryTag. *compound* (num-from-byte-array chunk-bytes 1 *short-length*) nil)
      nil)))