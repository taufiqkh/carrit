(ns carrit.chunk-world
  "Manages chunks in a world map."
  (:use carrit.region-file
        carrit.byte-convert)
  (:import com.quiptiq.carrit.ChunkWorld
           com.quiptiq.carrit.Chunk)
  (:gen-class
    :constructors {[] []}
    :init init
    :methods [[loadChunkWorld [String] com.quiptiq.carrit.ChunkWorld]]
    ))
; Java interoperable class for exposing chunk API

(def *minecraft-dir* "Whole New World")

(def ^{:doc "Number of blocks along the x axis of a chunk"} *chunk-size-x* 16)
(def ^{:doc "Number of blocks along the y axis of a chunk"} *chunk-size-y* 128)
(def ^{:doc "Number of blocks along the z axis of a chunk"} *chunk-size-z* 16)

(defn -init [] nil)

(def ^{:doc "Default sea level for dummy terrain generation"} *default-sea-level* 63)

(defn gen-dummy-block-ids []
  (concat (repeat *default-sea-level* (byte 1)) (repeat (- *chunk-size-y* *default-sea-level*) (byte 0))))

(defn gen-dummy-data [column]
  "Generate dummy blocks for the chunk, distributed according to the following:
Blocks[ y + z * ChunkSizeY(=128) + x * ChunkSizeY(=128) * ChunkSizeZ(=16) ]"
  (byte-array (reduce concat [] (repeat (* *chunk-size-x* *chunk-size-z*) column))))

(defn dummy-chunk [chunk-world x y z]
  (reify Chunk
    ; Ids of the blocks in this chunk.
    (getBlockIds [_] ;(byte-array (gen-dummy-block-ids)));
                 (gen-dummy-data (gen-dummy-block-ids)))
    ; Ancillary data for the blocks in this chunk, 4 bits per block.
    (getBlockData [_] (gen-dummy-data (repeat (/ *chunk-size-y* 2) 0)))
    ; Amount of sun- or moonlight hitting each block, 4 bits per block.
    (getSkyLight [_]
                 (gen-dummy-data (repeat (/ *chunk-size-y* 2) (unsigned-byte 0xFF))))
    ; Amount of light emitted per block, 4 bits per block.
    (getBlockLight [_] (gen-dummy-data (repeat (/ *chunk-size-y* 2) 0)))
    ; The lowest level in each column in where the light from the sky
    ; is at full strength. This is arranged Z, X.
    (getHeightMap [_] (byte-array (repeat (* *chunk-size-x* *chunk-size-z*) *default-sea-level*)))
    ; Entities in the chunk.
    (getEntitities [_] [])
    ; Tile entities in the chunk.
    (getTileEntities [_] [])
    ; Tick when the chunk was last saved.
    (getLastUpdate [_] nil)
    ; X position of the chunk.
    (getXPos [_] x)
    ; Y position of the chunk.
    (getYPos [_] y)
    ; Z position of the chunk.
    (getZPos [_] z)
    ; Whether or not the terrain in this chunk was populated with
    ; special things. (Ores, special blocks, trees, dungeons, flowers,
    ; waterfalls, etc.)
    (isTerrainPopulated [_] true)
    ; ChunkWorld that is managing the chunk, null if it has not been assigned.
    (getWorld [_] chunk-world)))

(defn -loadChunkWorld [this world-name]
  (let [loaded-world
        (if-let [save-dir (load-save-dir world-name)]
          (let [origin-descriptor (create-file-descriptor 0 0 0)]
            (read-region-file origin-descriptor ((save-dir :region-map) (origin-descriptor :filename))))
        nil)]
    (reify ChunkWorld
      (hasChunk [this x y z]
                true)
      (getChunk [this x y z] (dummy-chunk this x y z))
      (getChunkSizeX [_] *chunk-size-x*)
      (getChunkSizeY [_] *chunk-size-y*)
      (getChunkSizeZ [_] *chunk-size-z*))))

(defn -main [& options]
  (-loadChunkWorld nil *minecraft-dir*))