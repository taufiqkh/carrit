(ns carrit.named-binary-tag
  "Named Binary Tag format functions, as conforming to the NBT format specified at http://mc.kev009.com/NBT"
  (:use clojure.tools.logging
        carrit.byte-convert))

(set! *warn-on-reflection* true)

; TODO: Add mapping to clojure entities

(def ^:const ^{:doc "Tag end, denoting the end of a compound tag list."} type-end 0)
(def ^:const ^{:doc "Single, signed byte."} type-byte 1)
(def ^:const ^{:doc "Signed short, 16 bits."} type-short 2)
(def ^:const ^{:doc "Signed int, 32 bits."} type-int 3)
(def ^:const ^{:doc "Signed long, 64 bits."} type-long 4)
(def ^:const ^{:doc "Float, 32 bits IEEE 754-2008."} type-float 5)
(def ^:const ^{:doc "Double, 64 bits IEEE 754-2008."} type-double 6)
(def ^:const ^{:doc "Byte array of unspecified format."} type-byte-array 7)
(def ^:const ^{:doc "String."} type-string 8)
(def ^:const ^{:doc "Sequential list of a specified type."} type-list 9)
(def ^:const ^{:doc "Compound tag, which is a sequential list of uniquely named tags."} type-compound 10)
(def ^:const ^{:doc "Length-prefixed array of signed, 4 byte integers"} type-int-array 11)

(def ^:const ^{:doc "Lengths for all fixed-length types"}
               type-lengths (hash-map type-end 1
                   type-byte 1
                   type-short short-length
                   type-int int-length
                   type-long long-length
                   type-float float-length
                   type-double double-length))

(def type-names (hash-map type-end "End"
                   type-byte "Byte"
                   type-short "Short"
                   type-int "Int"
                   type-long "Long"
                   type-float "Float"
                   type-double "Double"
                   type-byte-array "Byte Array"
                   type-string "String"
                   type-list "List"
                   type-compound "Compound"
                   type-int-array "Int Array"))

(def ^{:doc "UTF-8 encoding" :tag String} utf-8 "UTF-8")

(defrecord NamedBinaryTag [type data name child-type])

(defn make-named-binary-tag
  "Creates a named binary tag with the given options. Type and data are
mandatory, while name and child-type default to nil. Child-type must be
specified for all lists."
  [type data & opts]
    (NamedBinaryTag. (num type) data (first opts) (second opts)))

(defn type-name [nbt] (get type-names (:type nbt)))

(defrecord ^{:doc "Extract from a byte array, containing copied data and the read length, in bytes"}
            Extract [data length])

(defn read-utf-8-segment
  "Reads the specified number of bytes at the given index in the array and
returns it as a UTF-8 string."
  [^bytes chunk-bytes idx byte-length]
  (let [^bytes buffer (copy-from-byte-array chunk-bytes idx byte-length)]
    (String. buffer #^String utf-8)))

(defn extract-utf-8-name
  "Read a tag name from the byte array at the specified index, returning the name and length, in bytes. The tag name is
expected to be prefixed by the length of the name."
  [^bytes chunk-bytes idx]
  (let [length (num-from-byte-array chunk-bytes idx short-length)]
    (Extract. (read-utf-8-segment chunk-bytes (+ idx short-length) length) length)))

(defmulti extract-from-byte-array
"Given a byte array, read the extract for the specified type and return that
extract and the length, in bytes, of the section that was read."
  (fn [tag-type _ _] tag-type))

(defn extract-nbt-from-byte-array
  "Reads an NBT from the given chunk-bytes byte array, starting at the specified index."
  [^bytes chunk-bytes idx]
  (let [nbt-type (unsigned-byte-to-num (aget chunk-bytes idx))]
    ; (logging/debug (apply format "nbt type is %d, index %d" [nbt-type idx]))
    (if (= (long nbt-type) type-end)
      (Extract. (make-named-binary-tag nbt-type nil) 1)
      (let [name-idx (inc idx)
            nbt-name (extract-utf-8-name chunk-bytes name-idx)
            ; Extract index starts after type, name length and name
            extract (extract-from-byte-array nbt-type chunk-bytes (+ idx 1 short-length (:length nbt-name)))
            nbt (:data extract)]
        ; (logging/debug nbt-name-data)
        (Extract. (assoc nbt :name (:data nbt-name))
                  ; tag id length + "name length" length + name length + extract length
                  (+ 1 short-length (:length nbt-name) (:length extract)))))))

(defn nbt-from-byte-array
  "Given a byte array, reads the NBT and all child NBTs from the specified index."
  [^bytes chunk-bytes idx]
  (:data (extract-nbt-from-byte-array chunk-bytes idx)))

; extract-from-byte-array(tag-type chunk-bytes idx)
; Creates an extract of the given tag type from the chunk-bytes byte array,
; starting from index idx.
(defmethod extract-from-byte-array type-byte [tag-type ^bytes chunk-bytes idx]
  (Extract. (make-named-binary-tag tag-type (aget chunk-bytes idx) nil) 1))

(defmethod extract-from-byte-array type-byte-array [tag-type ^bytes chunk-bytes idx]
  (let [length (num-from-byte-array chunk-bytes idx int-length)
        start-idx (+ idx int-length)]
    (Extract. (make-named-binary-tag tag-type (copy-from-byte-array chunk-bytes start-idx length))
              (+ int-length length))))

(defmethod extract-from-byte-array type-string [tag-type ^bytes chunk-bytes idx]
  (let [length (num-from-byte-array chunk-bytes idx short-length)]
    (Extract. (make-named-binary-tag tag-type (read-utf-8-segment chunk-bytes (+ idx short-length) length))
              (+ short-length length))))

(defmethod extract-from-byte-array type-list [tag-type ^bytes chunk-bytes idx]
  (let [list-tag-type (unsigned-byte-to-num (aget chunk-bytes idx))
        list-length (num-from-byte-array chunk-bytes (inc idx) int-length)]
    (loop [num-left list-length next-idx (+ idx 1 int-length) acc []]
      (if (zero? num-left)
        (Extract. (make-named-binary-tag tag-type acc nil list-tag-type) (- next-idx idx))
        (let [extract-data (extract-from-byte-array list-tag-type chunk-bytes next-idx)]
          (recur (dec num-left) (+ next-idx (:length extract-data)) (conj acc (:data extract-data))))))))

(defmethod extract-from-byte-array type-compound [tag-type ^bytes chunk-bytes idx]
  (loop [extract (extract-nbt-from-byte-array chunk-bytes idx) children {} length 0]
    (let [nbt (:data extract) nbt-length (long (:length extract))] 
      (if (= (:type nbt) type-end)
        (Extract. (make-named-binary-tag tag-type children) (+ length nbt-length))
        (recur (extract-nbt-from-byte-array chunk-bytes (+ idx length nbt-length))
               (assoc children (:name nbt) nbt)
               (+ length nbt-length))))))

(defmethod extract-from-byte-array type-int-array [tag-type ^bytes chunk-bytes idx]
  (let [length (num-from-byte-array chunk-bytes idx int-length)
        data (int-array length)]
    (doseq [^Integer int-idx (range 0 length)]
             (aset data int-idx ^Integer (num-from-byte-array chunk-bytes (+ idx int-length (* int-idx int-length)) int-length)))
    (Extract. (make-named-binary-tag tag-type data) (+ int-length (* int-length length)))))

(defmethod extract-from-byte-array type-float [tag-type ^bytes chunk-bytes idx]
  (Extract. (make-named-binary-tag tag-type (byte-array-to-float chunk-bytes idx)) float-length))

(defmethod extract-from-byte-array type-double [tag-type ^bytes chunk-bytes idx]
  (Extract. (make-named-binary-tag tag-type (byte-array-to-double chunk-bytes idx)) double-length))

(defmethod extract-from-byte-array :default [tag-type ^bytes chunk-bytes idx]
  (let [type-length (get type-lengths (int tag-type))]
    (Extract. (make-named-binary-tag tag-type (num-from-byte-array chunk-bytes idx type-length)) type-length)))

(defn collect-pre-output
  "Collects the lengths of the given nbt and its children"
  [nbt output]
  (let [type (:type nbt)]
    (if (contains? type-lengths type)
      (type-lengths type)
      (case type
              type-list (* (type-lengths (:child-type nbt)) (count (:data nbt)))
              type-int-array (* int-length (alength ^ints (:data nbt)))
              type-byte-array (alength ^bytes (:data nbt))
        (mapcat collect-pre-output (:data nbt))))))

(defn collect-pre-named-output [nbt output]
  (let [type (:type nbt)
        name (:name nbt)
        data (:data nbt)]
    (if (= type type-compound)
      (mapcat collect-pre-output data))))

(defn nbt-as-byte-arrays
  "Given a root NamedBinaryTag, creates a byte array in the NBT format. Root must be a compound tag."
  [nbt]
  (let [length (collect-pre-named-output nbt [])
        ]
    nil))

(defn traverse!
  "Traverses the given NBT, executing traversal-fn for side effects at each node. Returns nil"
  [nbt traversal-fn]
  (traversal-fn nbt)
  (if (contains? #{type-byte-array type-list type-int-array} (:type nbt))
    (dorun (map #(traverse! % traversal-fn) (:data nbt)))
    (if (= type-compound (:type nbt))
      (dorun (map #(traverse! % traversal-fn) (:data (vals nbt))))
      nil)))