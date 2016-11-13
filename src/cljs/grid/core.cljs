(ns grid.core
  (:require
   [clojure.string :refer [join split trim]]
   [cljs.reader :refer [read-string]]
   [hiccups.runtime :as hrt]
   [bigsky.aui.util :refer [spy]]))

(def grid-cols 12)

(def vcat (comp vec concat))

(def sizes [:xs :sm :md :lg])

(def sizes-index {:xs 0 :sm 1 :md 2 :lg 3})

(defn size-prior
  [size]
  (if-let [cur (sizes-index size)]
    (if (> cur 0)
      (sizes (dec cur)))))

(defn sizes-up-to
  [size]
  (reverse (subvec sizes 0 (inc (sizes-index size)))))

(def sizes-up-to-memo (memoize sizes-up-to))

(defn sizes-after
  [size]
  (if (= size :lg)
    []
    (subvec sizes (inc (sizes-index size)))))

(defn col-for-media
  [col media]
  (let [found (first (keep #(% col) (sizes-up-to-memo media)))]
    (if found
      found
      [nil grid-cols])))

(defn final-col-for-media
  [col media]
  (let [medias (keep #(% col) (sizes-up-to-memo media))
        offset (first (keep #(% 0) medias))
        width  (first (keep #(% 1) medias))]
    [(or offset 0) (or width 12)]))

(defn cols-for-media
  [row media]
  (map #(col-for-media % media) (:cols row)))

(defn total-cols-used
  [row media]
  (apply + (flatten (cols-for-media row media))))

(defn valid-size?
  [size]
  (not (nil? (sizes-index size))))

(defn all-true?
  [bools]
  (apply (every-pred true?) bools))

(defn between?
  [n low high]
  (and (>= n low)
       (<= n high)))

(defn valid-dim?
  [size dim low high]
  (or (nil? dim)
      (and (integer? dim)
           (between? dim low high))))

(defn valid-media?
  [[size offset width]]
  (and (valid-size? size)
       (valid-dim? size offset 0 12)
       (valid-dim? size width 1 12)))

(defn valid-layout-col?
  [col]
  (when (vector? col)
    (all-true?
     (map valid-media?
          (if (string? (first col))
            (rest col)
            col)))))

(defn valid-layout-row?
  [row]
  (when (vector? row)
    (all-true?
     (map valid-layout-col?
          (if (string? (first row))
            (rest row)
            row)))))

(defn valid-layout?
  [data]
  ;;[ [string? [string? [(:xs :sm :md :g) 0-12 0-12]]]]
  (and (vector? data)
       (all-true? (map valid-layout-row? data))))

(defn edn->col
  [data]
  (let [[name medias] (if (string? (first data))
                        [(first data) (rest data)]
                        [false data])]
    (apply (partial assoc {:name name})
           (apply vcat (map (fn [[s o w]] [s [o w]]) medias)))))

(defn edn->row
  [data]
  (let [[name cols] (if (string? (first data))
                      [(first data) (rest data)]
                      [false data])]
    {:name name
     :cols (map-indexed (fn [i c] (assoc c :pos i))
                        (map edn->col cols))
     :wrap false}))

(defn size-classes [c ignore-grid-classes]
  (remove nil?
          (flatten
           (concat
            (if (:name c) [(split (:name c) #"\s+")] [])
            (when (not ignore-grid-classes)
              (map (fn [s]
                     (if (s c)
                       (let [[offset width] (s c)]
                         [(when (not (nil? offset))
                            (str "col-" (name s) "-offset-" offset))
                          (when (not (nil? width))
                            (str "col-" (name s) "-" width))])))
                   sizes))))))

(defn layout->html
  ([rows content-fn ignore-grid-classes]
     (let [row-class (if ignore-grid-classes "" "row")]
       (map
        (fn [r]
          (conj (if (:name r)
                  [:div {:class (trim (join " " [row-class (:name r)]))}]
                  [:div {:class row-class}])
                (map (fn [c]
                       (let [all-classes (join " " (size-classes c ignore-grid-classes))
                             col [:div
                                  (if (not= (count all-classes) 0)
                                    {:class (join " " (size-classes c ignore-grid-classes))}
                                    {})]]
                         (if (nil? content-fn)
                           col
                           (conj col (content-fn r c)))))
                     (:cols r))))
        rows)))
  ([rows content-fn]
     (layout->html rows content-fn false))
  ([rows]
     (layout->html rows nil false)))

(defn layout->less-mixin
  [rows]
  (->>
   (for [row rows]
     [(str "." (:name row) " {")
      (str "  .make-row();")
      (for [col (:cols row)]
        [(str "  ." (:name col) " {")
         (for [size sizes :when (col size)]
           (let [[offset width] (col size)]
             [(when (not (nil? offset))
                (str "    .make-" (name size) "-column-offset(" offset ");"))
              (when (not (nil? width))
                (str "    .make-" (name size) "-column(" width ");"))]))
         "  }"])
      "}"])
   (flatten)
   (remove nil?)
   (join "\n")))

(defn edn-string->layout
  [edn-string]
  (let [rows (read-string edn-string)]
    (when (valid-layout? rows)
      (map edn->row rows))))

(defn edn->html
  [edn]
  (hrt/render-html (layout->html
                    edn
                    (fn [row col]
                      [:div.wrap {:row (name (:id row))
                                  :col (name (:id col))}]))))

(defn edn-string->html
  [edn-string]
  (edn->html (edn-string->layout edn-string)))

(defn percolate
  [col media]
  (let [percolated
        (into
         {}
         (filter (fn [[k v]] (not= v [nil nil]))
                 (for [[k v] col]
                   (do
                     (if (and (k sizes-index) (not= k :xs)) ;;note that :xs is excluded!
                       (let [prior-size (final-col-for-media col (size-prior k))
                             check-size (if (or (not= k :sm)
                                                (and (= k :sm)
                                                     (:xs col)
                                                     ((:xs col) 1)))
                                          prior-size
                                          [nil nil])]
                         (let [[offset width] v]
                           (if prior-size
                             [k [(if (= (check-size 0) offset)
                                   nil
                                   offset)
                                 (if (= (check-size 1) width)
                                   nil
                                   width)]]
                             [k [offset width]])))
                       [k v])))))]
    (if (and (not (percolated :xs))
             (not (percolated :sm))
             (not (percolated :md))
             (not (percolated :lg)))
      (assoc percolated :xs [nil 12])

      (if (and (percolated :xs)
               (= ((percolated :xs) 1) 12)
               (or (percolated :sm)
                   (percolated :md)
                   (percolated :lg)))
        (assoc percolated :xs [nil nil])
        percolated))))

(defn layout->jade
  [rows include-container ignore-grid-classes]
  (let [container (if include-container ".container\n" "")
        row-class (if ignore-grid-classes [] ["row"])
        row-prefix (if include-container "  " "")
        col-prefix (if include-container "    " "  ")]
    (str container
         (->> rows
              (map
               (fn [row]
                 (str row-prefix
                      (str "." (trim (join "." (if (:name row)
                                                 (conj row-class (:name row))
                                                 row-class))) "\n")
                      col-prefix
                      (->> (:cols row)
                           (map
                            (fn [col]
                              (str "." (join "." (size-classes col ignore-grid-classes)))))
                           (join (str "\n" col-prefix))))))
              (join "\n")))))

(defn layout->edn
  [rows]
  (vec (for [row rows]
         (vcat (if (:name row) [(:name row)] [])
               (for [col (:cols row)]
                 (vcat (if (:name col) [(:name col)] [])
                       (for [size sizes :when (size col)]
                         (vcat [size] (size col)))))))))


(def ebo [:.edn-bracket-open.tag "["])
(def ebc [:.edn-bracket-close.tag "]"])

(defn sizes->html
  [sizes]
  (vcat  [:ul.edn-media]
         (vec (for [[size offset width] sizes]
                [:li ebo
                 [:span.edn-kw.atn (str size)]
                 [:span.edn-kw.atv (or offset "nil")]
                 [:span.edn-kw.atv.edn-width (or width "nil")] ebc]))))

(defn cols->html
  [cols]
  (let [els (vcat [:ul.edn-cols]
                  (vec (for [col cols]
                         (if (string? (first col))
                           [:li ebo [:.edn-name.atv (str \" (first col) \")] (sizes->html (rest col)) ebc]
                           [:li ebo (sizes->html col) ebc]))))]
    (if (> (count els) 1)
      (assoc els
        (dec (count els))
        (conj (last els) ebc))
      (conj els ebc))))

(defn rows->html
  [rows]
  (vcat [:.all-edn ebo
         (vcat [:ul.edn-rows]
               (let [els
                     (vec (for [row rows]
                            (if (string? (first row))
                              [:li ebo [:.edn-name.atv (str \" (first row) \")] (cols->html (rest row))]
                              [:li ebo (cols->html row)])))]
                 (if (> (count els) 0)
                   (assoc els
                     (dec (count els))
                     (let [last-row (last els)
                           path [(dec (count last-row))
                                 (dec (count (last last-row)))
                                 (dec (count (last (last last-row))))]
                           last-col (get-in last-row path)]
                       (if (vector? last-col)
                         (assoc-in last-row
                                   path
                                   (conj last-col ebc))
                         (conj last-row ebc))))
                   (conj els ebc))))]))
