(ns carrit.test-core
  (:require [carrit.region-file :as region-file])
  (:use clojure.test
        carrit.byte-convert
        carrit.named-binary-tag)
  (:import java.util.Arrays))

(defn vec-as-byte-array [vec-to-convert]
  (byte-array (reduce #(conj %1 (byte %2)) [] vec-to-convert)))

(deftest test-expand-arrays
  (let [arrays [(vec-as-byte-array [1 2 3]) (vec-as-byte-array [4 5 6 7])]
        ^bytes expanded-array (region-file/expand-arrays arrays 5)]
    (is (= 5 (count expanded-array)))
    (is (Arrays/equals expanded-array ^bytes (vec-as-byte-array [1 2 3 4 5])))))

(deftest test-num-from-byte-array
  "Number from a byte array in the region file format"
  (is (= 262405 (num-from-byte-array (vec-as-byte-array [4 1 5]) 0 3)))
  (is (= 2560 (num-from-byte-array (vec-as-byte-array [0 0xa 0]) 0 3)))
  (is (= 2728 (num-from-byte-array (vec-as-byte-array [0 0xa (unsigned-byte 0xa8)]) 0 3))))

(defn reconstruct-utf-8 [string]
  (String. (byte-array (byte-array-to-vector (.getBytes string) 0 (.length string) [])) "UTF-8"))

(deftest test-str-from-byte-array
  "String from a byte array"
  (let [test-string "This is a string"]
    (is (= test-string (reconstruct-utf-8 test-string))))
  (let [test-string "κόσμε"]
    (is (= test-string (reconstruct-utf-8 test-string)))))