;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape-tree
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [clojure.spec.alpha :as s]))

(s/def ::objects (s/map-of uuid? ::cts/shape))

(defn add-shape
  "Insert a shape in the tree, at the given index below the given parent or frame.
  Update the parent as needed."
  [id shape container frame-id parent-id index ignore-touched]
  (let [update-parent-shapes
        (fn [shapes]
          ;; Ensure that shapes is always a vector.
          (let [shapes (into [] shapes)]
            (cond
              (some #{id} shapes)
              shapes

              (nil? index)
              (conj shapes id)

              :else
              (cph/insert-at-index shapes index [id]))))

        update-parent
        (fn [parent]
          (-> parent
              (update :shapes update-parent-shapes)
              (update :shapes d/vec-without-nils)
              (cond-> (and (:shape-ref parent)
                           (not= (:id parent) frame-id)
                           (not ignore-touched))
                (-> (update :touched cph/set-touched-group :shapes-group)
                    (dissoc :remote-synced?)))))

        ;; TODO: this looks wrong, why we allow nil values?
        update-objects
        (fn [objects parent-id]
          (if (and (or (nil? parent-id) (contains? objects parent-id))
                   (or (nil? frame-id) (contains? objects frame-id)))
            (-> objects
                (assoc id (-> shape
                              (assoc :frame-id frame-id)
                              (assoc :parent-id parent-id)
                              (assoc :id id)))
                (update parent-id update-parent))
            objects))

        parent-id (or parent-id frame-id)]

    (update container :objects update-objects parent-id)))

(defn set-shape
  "Replace a shape in the tree with a new one"
  [container shape]
  (assoc-in container [:objects (:id shape)] shape))

(defn get-frames
  "Retrieves all frame objects as vector"
  [objects]
  (or (-> objects meta ::index-frames)
      (let [lookup (d/getf objects)
            xform  (comp (remove #(= uuid/zero %))
                         (keep lookup)
                         (filter cph/frame-shape?))]
        (->> (keys objects)
             (into [] xform)))))

(defn get-frames-ids
  "Retrieves all frame ids as vector"
  [objects]
  (->> (get-frames objects)
       (mapv :id)))

(defn get-nested-frames
  [objects frame-id]
  (into #{}
        (comp (filter cph/frame-shape?)
              (map :id))
        (cph/get-children objects frame-id)))

(defn get-root-frames-ids
  "Retrieves all frame objects as vector. It is not implemented in
  function of `cph/get-immediate-children` for performance
  reasons. This function is executed in the render hot path."
  [objects]
  (let [add-frame
        (fn [result shape]
          (cond-> result
            (cph/frame-shape? shape)
            (conj (:id shape))))]
    (cph/reduce-objects objects (complement cph/frame-shape?) add-frame [])))

(defn get-root-objects
  "Get all the objects under the root object"
  [objects]
  (let [add-shape
        (fn [result shape]
          (conj result shape))]
    (cph/reduce-objects objects (complement cph/frame-shape?) add-shape [])))

(defn get-root-shapes
  "Get all shapes that are not frames"
  [objects]
  (let [add-shape
        (fn [result shape]
          (cond-> result
            (not (cph/frame-shape? shape))
            (conj shape)))]
    (cph/reduce-objects objects (complement cph/frame-shape?) add-shape [])))

(defn get-root-shapes-ids
  [objects]
  (->> (get-root-shapes objects)
       (mapv :id)))

(defn get-base
  [objects id-a id-b]

  (let [[parents-a parents-a-index] (cph/get-parent-ids-with-index objects id-a)
        [parents-b parents-b-index] (cph/get-parent-ids-with-index objects id-b)

        parents-a (cons id-a parents-a)
        parents-b (into #{id-b} parents-b)

        ;; Search for the common frame in order
        base (or (d/seek parents-b parents-a) uuid/zero)

        idx-a (get parents-a-index base)
        idx-b (get parents-b-index base)]

    [base idx-a idx-b]))

(defn is-shape-over-shape?
  [objects base-shape-id over-shape-id]

  (let [[base index-a index-b] (get-base objects base-shape-id over-shape-id)]
    (cond
      ;; The base the base shape, so the other item is bellow
      (= base base-shape-id)
      false

      ;; The base is the testing over, so it's over
      (= base over-shape-id)
      true

      ;; Check which index is lower
      :else
      ;; If the base is a layout we should check if the z-index property is set
      (let [[z-index-a z-index-b]
            (if (ctl/any-layout? objects base)
              [(ctl/layout-z-index objects (dm/get-in objects [base :shapes index-a]))
               (ctl/layout-z-index objects (dm/get-in objects [base :shapes index-b]))]
              [0 0])]

        (if (= z-index-a z-index-b)
          (< index-a index-b)
          (< z-index-a z-index-b))))))

(defn sort-z-index
  ([objects ids]
   (sort-z-index objects ids nil))

  ([objects ids {:keys [bottom-frames?] :as options}]
   (letfn [(comp [id-a id-b]
             (let [frame-a? (= :frame (dm/get-in objects [id-a :type]))
                   frame-b? (= :frame (dm/get-in objects [id-b :type]))]
               (cond
                 (= id-a id-b)
                 0

                 (and (not frame-a?) frame-b?)
                 (if bottom-frames? -1 1)

                 (and frame-a? (not frame-b?))
                 (if bottom-frames? 1 -1)

                 ;; When comparing frames we invert the order if the flag `bottom-frames?` is on
                 (and frame-a? frame-b? bottom-frames?)
                 (if (is-shape-over-shape? objects id-b id-a) 1 -1)

                 (is-shape-over-shape? objects id-b id-a)
                 -1

                 :else
                 1)))]
     (sort comp ids))))

(defn frame-id-by-position
  [objects position]
  (assert (gpt/point? position))
  (let [top-frame
        (->> (get-frames-ids objects)
             (sort-z-index objects)
             (d/seek #(and position (gsh/has-point? (get objects %) position))))]
    (or top-frame uuid/zero)))

(defn frame-by-position
  [objects position]
  (let [frame-id (frame-id-by-position objects position)]
    (get objects frame-id)))

(defn all-frames-by-position
  [objects position]
  (->> (get-frames-ids objects)
       (filter #(and position (gsh/has-point? (get objects %) position)))
       (sort-z-index objects)))

(defn top-nested-frame
  "Search for the top nested frame for positioning shapes when moving or creating.
  Looks for all the frames in a position and then goes in depth between the top-most and its
  children to find the target."
  ([objects position]
   (top-nested-frame objects position nil))

  ([objects position excluded]
   (assert (or (nil? excluded) (set? excluded)))

   (let [frame-ids (cond->> (all-frames-by-position objects position)
                     (some? excluded)
                     (remove excluded))

         frame-set (set frame-ids)]

     (loop [current-id (first frame-ids)]
       (let [current-shape (get objects current-id)
             child-frame-id (d/seek #(contains? frame-set %)
                                    (-> (:shapes current-shape) reverse))]
         (if (nil? child-frame-id)
           (or current-id uuid/zero)
           (recur child-frame-id)))))))

(defn top-nested-frame-ids
  "Search the top nested frame in a list of ids"
  [objects ids]

  (let [frame-ids (->> ids (filter #(cph/frame-shape? objects %)))
        frame-set (set frame-ids)]
    (loop [current-id (first frame-ids)]
      (let [current-shape (get objects current-id)
            child-frame-id (d/seek #(contains? frame-set %)
                                   (-> (:shapes current-shape) reverse))]
        (if (nil? child-frame-id)
          (or current-id uuid/zero)
          (recur child-frame-id))))))

(defn get-viewer-frames
  ([objects]
   (get-viewer-frames objects nil))

  ([objects {:keys [all-frames?]}]
   (into []
         (comp (map (d/getf objects))
               (if all-frames?
                 identity
                 (remove :hide-in-viewer)))
         (sort-z-index objects (get-frames-ids objects)))))

(defn start-page-index
  [objects]
  (with-meta objects {::index-frames (get-frames (with-meta objects nil))}))

(defn update-page-index
  [objects]
  (with-meta objects {::index-frames (get-frames (with-meta objects nil))}))

(defn update-object-indices
  [file page-id]
  (update-in file [:pages-index page-id :objects] update-page-index))

(defn rotated-frame?
  [frame]
  (not (mth/almost-zero? (:rotation frame 0))))

(defn clone-object
  "Gets a copy of the object and all its children, with new ids
  and with the parent-children links correctly set. Admits functions
  to make more transformations to the cloned objects and the
  original ones.

  Returns the cloned object, the list of all new objects (including
  the cloned one), and possibly a list of original objects modified.

  The list of objects are returned in tree traversal order, respecting
  the order of the children of each parent."

  ([object parent-id objects update-new-object]
   (clone-object object parent-id objects update-new-object (fn [object _] object) nil))

  ([object parent-id objects update-new-object update-original-object]
   (clone-object object parent-id objects update-new-object update-original-object nil))

  ([object parent-id objects update-new-object update-original-object force-id]
   (let [new-id (or force-id (uuid/next))]
     (loop [child-ids (seq (:shapes object))
            new-direct-children []
            new-children []
            updated-children []]

       (if (empty? child-ids)
         (let [new-object (cond-> object
                            true
                            (assoc :id new-id
                                   :parent-id parent-id)

                            (some? (:shapes object))
                            (assoc :shapes (mapv :id new-direct-children)))

               new-object  (update-new-object new-object object)
               new-objects (into [new-object] new-children)

               updated-object  (update-original-object object new-object)
               updated-objects (if (identical? object updated-object)
                                 updated-children
                                 (into [updated-object] updated-children))]

           [new-object new-objects updated-objects])

         (let [child-id (first child-ids)
               child    (get objects child-id)
               _        (us/assert! ::us/some child)

               [new-child new-child-objects updated-child-objects]
               (clone-object child new-id objects update-new-object update-original-object)]

           (recur
            (next child-ids)
            (into new-direct-children [new-child])
            (into new-children new-child-objects)
            (into updated-children updated-child-objects))))))))

(defn generate-shape-grid
  "Generate a sequence of positions that lays out the list of
  shapes in a grid of equal-sized rows and columns."
  [shapes start-pos gap]
  (let [shapes-bounds (map gsh/bounding-box shapes)

        grid-size   (mth/ceil (mth/sqrt (count shapes)))
        row-size    (+ (apply max (map :height shapes-bounds))
                       gap)
        column-size (+ (apply max (map :width shapes-bounds))
                       gap)

        next-pos (fn [position]
                   (let [counter (inc (:counter (meta position)))
                         row     (quot counter grid-size)
                         column  (mod counter grid-size)
                         new-pos (gpt/add start-pos
                                          (gpt/point (* column column-size)
                                                     (* row row-size)))]
                     (with-meta new-pos
                                {:counter counter})))]
    (iterate next-pos
             (with-meta start-pos
                        {:counter 0}))))

