(ns shoelace.client
  (:require
   [clojure.string :refer [join split]]
   [hiccups.runtime :as hrt]
   [dommy.core :as dom]
   [dommy.utils :refer [dissoc-in]]
   [cljs.core.async :refer [>! <! put! chan sliding-buffer]]
   [bigsky.aui.util :refer [event-chan applies jget jset watch-change watch-change-in
                            watch-change-when insert-after go-alphabet spy]]
   [bigsky.aui.draggable :refer [draggable]]
   [ajax.core :refer [GET POST]]
   [gist.core :as gist]
   [ednio.core :as ednio]
   [cljs.reader :refer [read-string]]
   [grid.core :as grid :refer [sizes sizes-index size-classes sizes-up-to sizes-up-to-memo total-cols-used
                               col-for-media cols-for-media grid-cols percolate layout->less-mixin layout->jade
                               layout->edn sizes->html cols->html rows->html edn->html
                               final-col-for-media sizes-after valid-layout? edn->row vcat size-prior]])
  (:require-macros
   [cljs.core.async.macros :refer [go]]
   [dommy.macros :refer [node sel sel1]]))

(def is-safari
  (let [ua (.toLowerCase js/navigator.userAgent)]
    (and (not= (.indexOf ua "safari") -1)
         (= (.indexOf ua "chrome") -1))))

(def transition-end (if is-safari "webkitTransitionEnd" "transitionend"))

(defn sels
  [names selectors]
  (zipmap names (for [s selectors] (sel1 s))))

(def col-height 150)

(def col-width 60)

(def col-margin-width 15)

(def snap-threshold 20)

(def body js/document.body)

(def id (atom 1))

(defn new-id! [prefix]
  (swap! id inc)
  (keyword (str prefix "-" @id)))

(def settings (atom {:media-mode :sm
                     :use-less-mixin false
                     :include-container true
                     :active-row :none
                     :output-mode :html
                     :gist-id false
                     :gist-version false}))

(def undo-history (atom []))

(def layout (atom []))

(def preview-data (atom {}))

(defn not-none?
  [x]
  (not (= :none x)))

(defn get-by-key
  [col attr val]
  (first (filter (fn [i] (= (attr i) val)) col)))

(defn get-by-id
  [col id]
  (get-by-key col :id id))

(defn get-row
  [row-id]
  (get-by-id @layout row-id))

(defn get-col
  [row-id col-id]
  (get-by-id (:cols (get-row row-id)) col-id))

(defn id->sel [id]
  (str "#" (name id)))

(defn get-el [id]
  (sel1 (id->sel id)))

(defn set-active-row!
  [row-id]
  (let [cur (:active-row @settings)
        cur-el (get-el cur)]
    (when cur-el
      (dom/remove-class! cur-el :active)))
  (swap! settings assoc :active-row row-id)
  (when (not-none? row-id)
    (dom/add-class! (get-el row-id) :active)))

(defn calc-col-unit []
  (+ col-margin-width col-width))

(defn get-class-el
  [col-id size type]
  (sel1 (str "#" (name col-id) " ." (name size) "-" (name type))))

(defn stop-propagation
  [e]
  (.stopPropagation e))

(defn update-col-for-media
  [row-id col-id media]
  (let [col (get-col row-id col-id)
        has-sizes (media col)
        has-offset (and has-sizes (has-sizes 0))
        has-width (and has-sizes (has-sizes 1))
        id (id->sel col-id)
        width-el (sel1 (str id " .width"))
        offset-el (sel1 (str id " .offset"))]
    ((if has-offset dom/remove-class! dom/add-class!) offset-el :no-changes)
    ((if has-width  dom/remove-class! dom/add-class!) width-el :no-changes)))

(defn update-cols-for-media
  [media]
  (let [col-unit (calc-col-unit)]
    (doseq [row @layout]
      (doseq [col (:cols row)]
        (let [widths (final-col-for-media (get-col (:id row) (:id col)) media)
              id (id->sel (:id col))
              width-el (sel1 (str id " .width"))
              offset-el (sel1 (str id " .offset"))
              has-sizes (not (nil? (media col)))]
          (update-col-for-media (:id row) (:id col) media)
          (applies dom/set-px!
                   [offset-el :width (* col-unit (widths 0))]
                   [width-el :width (- (* col-unit (widths 1)) col-margin-width)]))))))

(defn listens!
  [el events handler]
  (doseq [event events]
    (dom/listen! el event handler)
    ))
(defn unlistens!
  [el events handler]
  (doseq [event events]
    (dom/unlisten! el event handler)))

(defn add-col!
  [e cols-el new-col-el row-id]
  (let [col-id (new-id! 'col)
        col-el (node [:.col {:id (name col-id)}])
        offset-el (node [:.offset])
        remove-el (node [:.remove [:i.icon-remove]])
        width-el (node [:.width])
        nested-el (node [:.nested.hidden [:i.icon-th]])
        classes-el (node [:.classes
                          [:.xs-width] [:.xs-offset]
                          [:.sm-width] [:.sm-offset]
                          [:.md-width] [:.md-offset]
                          [:.lg-width] [:.lg-offset]])
        name-el (node [:input.col-name {:placeholder "col"}])
        els {:width width-el
             :offset offset-el}
        type-pos {:offset 0
                  :width 1}
        offset-handle-el (node [:.offset-handle])
        row (get-row row-id)
        total-cols (fn [] (total-cols-used (get-row row-id) (@settings :media-mode)))

        handle-remove (fn [e]
                        (stop-propagation e)
                        (let [row (get-row row-id)
                              path [(:pos row) :cols]]
                          (swap! layout assoc-in path
                                 (into []
                                       (map-indexed (fn [i c] (assoc c :pos i))
                                                    (filter (fn [c] (not (= (:id c) col-id)))
                                                            (get-in @layout path)))))
                          (dom/remove! col-el)

                          (when (zero? (count (get-in @layout path)))
                            (dom/add-class! new-col-el :no-cols))))
        draw-class-type (fn [media type size]
                          (dom/set-text! (get-class-el col-id media type)
                                         (if (and (= type :offset) (nil? size))
                                           ""
                                           (str (name media) "-"
                                                (when (= type :offset) "offset-")
                                                size))))
        draw-classes (fn []
                       (let [col (get-col row-id col-id)]
                         (doseq [media sizes]
                           (applies dom/set-text!
                                    [(get-class-el col-id media :offset) ""]
                                    [(get-class-el col-id media :width)  ""])
                           (when (media col)
                             (let [[offset width] (media col)]
                               (when offset (draw-class-type media :offset offset))
                               (when width (draw-class-type media :width width)))))))

        handle-drag (fn [type e]
          (stop-propagation e)
          (.preventDefault e)
          (set-active-row! row-id)
          (let [start-x (aget e "clientX")
                start-w (dom/px (els type) "width")
                media (@settings :media-mode)
                col-unit (calc-col-unit)
                row (get-row row-id)
                col (get-col row-id col-id)
                cur-cols-used (col-for-media col media)
                fcols (final-col-for-media col media)
                tfcols (+ (or (fcols 0) 0) (or (fcols 1) 0))
                dim-pos (type-pos type)
                dim-pos-opp (type-pos (if (= type :offset) :width :offset))
                max-width (- (* (- grid-cols (fcols dim-pos-opp)) col-unit) col-margin-width)
                snap! (fn []
                        (let [w (+ (if (= type :offset) col-margin-width 0)
                                   (dom/px (els type) "width"))
                              c (quot w col-unit)
                              r (mod w col-unit)
                              new-width (if (= type :offset)
                                          (max c 0)
                                          ((if (> r snap-threshold) + max) c 1))
                              path [(:pos row) :cols (:pos col) media]
                              new-dims (assoc-in (or (get-in @layout path)
                                                     [nil nil])
                                                 [(type-pos type)]
                                                 new-width)]

                          (when (not= (fcols (type-pos type)) (new-dims (type-pos type)))
                            (swap! layout assoc-in path new-dims))

                          (swap! layout assoc-in [(:pos row) :cols (:pos col)]
                                 (percolate (get-col row-id col-id) media))

                          (draw-classes)

                          (update-col-for-media row-id col-id media)
                          (dom/add-class! (els type) :easing)

                          (dom/set-px! (els type)
                                       :width
                                       (- (* new-width col-unit)
                                          (if (= type :width) col-margin-width 0)))))
                valid-step (fn [width]
                             (<= (+ (- width (dec (fcols dim-pos)))
                                    tfcols)
                                 grid-cols))
                move-handler (fn [e]
                               (let [dx (- (aget e "clientX") start-x)
                                     sdx (+ start-w dx)
                                     nw (if (> sdx  max-width) max-width sdx)
                                     qw (quot nw col-unit)]
                                 (when (valid-step qw)
                                   (draw-class-type media type qw)
                                   (dom/set-px! (els type) :width nw))))
                stop-handler (fn [e]
                               (unlistens! js/document [:mousemove :touchmove] move-handler)
                               (snap!))]
            (when (or (not= media :xs) (= type :width))
              (dom/add-class! col-el :dragging)
              (dom/remove-class! (els type) :easing)
              (listens! js/document [:mousemove :touchmove] move-handler)
              (dom/listen-once! js/document :mouseup stop-handler))))]

    (swap! layout update-in [(:pos row) :cols] conj
           {:id col-id
            :name (if (@settings :use-less-mixin) (str "col-" (:pos row) "-" (count (:cols row))) false)
            :from-mixin (@settings :use-less-mixin)
            :pos (count (:cols row))
            (@settings :media-mode) [nil 1]})

    (applies dom/append!
             [width-el nested-el name-el classes-el offset-handle-el]
             [col-el offset-el width-el remove-el]
             [cols-el col-el])

    (dom/insert-before! col-el new-col-el)
    (dom/remove-class! new-col-el :no-cols)

    (applies #(listens! %1 [:mousedown :touchstart] %2)
             [remove-el        #(handle-remove %)]
             [offset-handle-el #(handle-drag :offset %)]
             [offset-el        #(handle-drag :offset %)]
             [width-el         #(handle-drag :width %)]
             [name-el          (fn [e] (stop-propagation e))])

    (dom/listen! name-el :change
      (fn [e]
        (let [row (get-row row-id)
              col (get-col row-id col-id)
              input-val(.-value name-el)
              use-mixin (and (@settings :use-less-mixin)
                             (zero? (count input-val)))
              new-name (if use-mixin
                         (str "col-" (:pos row) "-" (:pos col))
                         input-val)]
          (when use-mixin (aset name-el "value" new-name))
          (swap! layout assoc-in [(:pos row) :cols (:pos col)]
                 (assoc col
                   :name (if (zero? (count new-name))
                           false
                           new-name)
                   :from-mixin use-mixin)))))

    (when e (handle-drag :width e))

    ;;if the layout changes we want to draw it again
    (watch-change-when layout
                       (fn [os ns]
                         (let [row (get-row row-id)
                               col (get-col row-id col-id)]
                           [(:pos row) :cols (:pos col)]))
                       (keyword col-id :change-col)
                       (fn [os ns]
                         (let [col (get-col row-id col-id)]
                           (aset name-el "value" (or (:name col) "")))
                         (draw-classes)))

    [col-id col-el (go-alphabet :draw-classes draw-classes) els name-el]))

(defn update-less-mixin-classes []
  (let [new-layout (vec (for [row @layout]
                          (let [cols (vec (for [col (:cols row)]
                                            (if (:from-mixin col)
                                              (assoc col :name (str "col-" (:pos row) "-" (:pos col)))
                                              col)))]
                            (if (:from-mixin row)
                              (assoc row
                                :name (str "row-" (:pos row))
                                :cols cols)
                              (assoc row
                                :cols cols)))))]
    (when (not= new-layout @layout)
      (reset! layout new-layout))))

(defn create-row []
  (let [row-id (new-id! "row")
        row-el (node [:.sl-row.easing {:id (name row-id)}])
        cols-el (node [:.cols])
        name-el (node [:input.row-name {:placeholder "Name Row"}])
        tools-el (node [:.tools])
        dupe-row-el (node [:span.dupe-row [:i.icon-double-angle-down]])
        clear-row-el (node [:span.clear-row [:i.icon-undo]])
        remv-row-el (node [:span.remv-row [:i.icon-remove]])
        new-col-el (node [:.new-col.no-cols])
        clear-el (node [:.clear])]

    (watch-change-when layout
                       (fn [os ns]
                         (let [row (get-row row-id)]
                           [(:pos row)]))
                       (keyword row-id :change-row)
                       (fn [os ns]
                         (let [row (get-row row-id)]
                           (aset name-el "value" (or (:name row) "")))))

    (applies dom/append!
             [cols-el new-col-el]
             [tools-el dupe-row-el clear-row-el remv-row-el]
             [row-el cols-el name-el tools-el clear-el])

    (applies dom/listen!
             [row-el :mousedown (fn [e]
                                  (stop-propagation e)
                                  (dom/remove-class! new-col-el :hidden)
                                  (set-active-row! row-id))]
             [name-el :change (fn [e] (let [row (get-row row-id)
                                           input-val (.-value name-el)
                                           use-mixin (and (@settings :use-less-mixin)
                                                          (zero? (count input-val)))
                                           new-name (if use-mixin
                                                      (str "row-" (:pos row))
                                                      input-val)]
                                       (when use-mixin (aset name-el "value" new-name))
                                       (swap! layout assoc-in [(:pos row)]
                                              (assoc (get-row row-id)
                                                :name (if (zero? (count new-name))
                                                        false
                                                        new-name)
                                                :from-mixin use-mixin))))]
             [new-col-el :mousedown (fn [e] (add-col! e cols-el new-col-el row-id))]

             [remv-row-el :mousedown (fn [e]
               (let [row (get-row row-id)]
                 (dom/add-class! row-el :removing)
                 (dom/listen-once! row-el transition-end
                   (fn [] (reset! layout
                                 (into []
                                       (map-indexed (fn [i r] (assoc r :pos i))
                                                    (filter (fn [r] (not (= (:id r) row-id)))
                                                            @layout))))
                     (dom/remove! row-el)))))]

             [clear-row-el :mousedown (fn [e]
               (let [row (get-row row-id)
                     media (:media-mode @settings)]
                 (swap! layout assoc-in [(:pos row) :cols]
                        (vec (for [col (row :cols)]
                               (percolate (dissoc col media) media))))
                 (update-cols-for-media media)))]

             [dupe-row-el :mousedown (fn [e]
               (let [row (get-row row-id)
                     [duped-row-id duped-row-el duped-cols-el duped-new-col-el duped-name-el] (create-row)]
                 (dom/insert-after! duped-row-el row-el)
                 (reset! layout
                         (into [] (map-indexed
                                   (fn [i r] (assoc r :pos i))
                                   (insert-after @layout (inc (:pos row))
                                                 {:id duped-row-id
                                                  :pos 0
                                                  :cols []}))))
                 (when (:name row)
                   (let [duped-row (get-row duped-row-id)
                         new-name (if (@settings :use-less-mixin)
                                    (str "row-" (:pos duped-row))
                                    (:name row))]
                     (swap! layout assoc-in [(:pos duped-row)]
                            (assoc duped-row
                              :name new-name
                              :from-mixin (@settings :use-less-mixin)))
                     (aset duped-name-el "value" new-name)))

                 (let [new-row (get-row duped-row-id)
                       col-unit (calc-col-unit)]
                   (doseq [col (:cols row)]
                     (let [[new-col-id new-col-el new-col-in-chan els col-name-el]
                           (add-col! false duped-cols-el duped-new-col-el duped-row-id)
                           path [(:pos new-row) :cols (:pos col)]
                           new-col-name (if (@settings :use-less-mixin)
                                          (str "col-" (:pos new-row) "-" (:pos col))
                                          (:name col))]
                       (swap! layout assoc-in path (get-col duped-row-id new-col-id)
                              :name new-col-name
                              :from-mixin (@settings :use-less-mixin))
                       (dom/add-class! (:width els) :easing)
                       (when new-col-name
                         (aset col-name-el "value" new-col-name))
                       (doseq [size sizes]
                         (when (size col)
                           (applies dom/set-px!
                                    [(:offset els) :width (* col-unit ((size col) 0))]
                                    [(:width els)  :width (- (* col-unit ((size col) 1))
                                                             col-margin-width)])
                           (swap! layout assoc-in (conj path size) (size col))))
                       (when (= grid-cols (total-cols-used (get-row duped-row-id)
                                                           (@settings :media-mode)))
                         (dom/add-class! duped-new-col-el :hidden))
                       (put! new-col-in-chan [:draw-classes])))
                   (when (@settings :use-less-mixin (update-less-mixin-classes))))))])

    [row-id row-el cols-el new-col-el name-el]))

(defn add-row! []
  (this-as new-row-el
    (let [[row-id row-el] (create-row)]
      (swap! layout conj {:id row-id
                          :pos (count @layout)
                          :cols []
                          :name (if (@settings :use-less-mixin)
                                  (str "row-" (count @layout))
                                  false)
                          :from-mixin (@settings :use-less-mixin)})
      (dom/insert-before! row-el new-row-el)
      (set-active-row! row-id))))

(defn make-options []
  (let [options-el (sel1 [:.options])
        ul (sel1 [:.options :ul])
        output-els {:html (sel1 :.output-html)
                    :jade (sel1 :.output-jade)
                    :haml (sel1 :.output-haml)
                    :edn  (sel1 :.output-edn)}
        els-to-mode (zipmap (vals output-els) (keys output-els))
        use-less-mixin-el (sel1 :.use-less-mixin)
        include-container-el (sel1 :.include-container)]

    (watch-change settings :output-mode :output-mode-done
                  (fn [old new]
                    (dom/remove-class! (output-els old) :active)
                    (dom/add-class! (output-els new) :active)))

    (dom/set-attr! include-container-el
                   :checked
                   (@settings :include-container))

    (applies dom/listen!
             [[options-el :li]
              :click
              (fn [e]
                (swap! settings assoc :output-mode (els-to-mode (aget e "target"))))]

             [use-less-mixin-el
              :change
              #(swap! settings assoc :use-less-mixin (aget use-less-mixin-el "checked"))]

             [include-container-el
              :change
              #(swap! settings assoc :include-container (aget include-container-el "checked"))])))

(defn make-collapse-pane
  [state workspace pane-el collapse-el]
  (dom/listen!
   collapse-el
   :click (fn []
            (let [collapsed (@settings state)
                  toggle-class! (if collapsed dom/remove-class! dom/add-class!)]
              (applies toggle-class!
                       [pane-el :collapsed]
                       [collapse-el :collapsed]
                       [workspace state])
              (dom/listen-once! pane-el transition-end
                                #(swap! settings assoc state (not collapsed)))))))

(def media-factor
  {:xs 0.02
   :sm 0.06
   :md 0.11
   :lg 0.15})

(defn make-media-previews []
  (let [preview-els (sels sizes (for [size sizes] (str ".preview." (name size) " .preview-rows")))]
    (go-alphabet
     :update #(do
                (doseq [[_ el] preview-els] (dom/set-text! el ""))
                (doseq [row @layout]
                  (doseq [[size el] preview-els]
                    (let [row-el (node [:.preview-row])
                          col-unit (* col-width (media-factor size))]
                      (dom/set-px! el :width (+ (* 3 grid-cols)
                                                (* grid-cols col-unit)))
                      (dom/append! el row-el)
                      (doseq [col (:cols row)]
                        (let [col-el (node [:.preview-col])
                              offset-el (node [:.preview-col-offset])
                              width-el (node [:.preview-col-width])
                              fwidths (final-col-for-media (get-col (:id row) (:id col)) size)]
                          (applies dom/append!
                                   [row-el col-el]
                                   [col-el offset-el]
                                   [col-el width-el])
                          (applies dom/set-px!
                                   [offset-el :width (+ (* (fwidths 0) 3)
                                                        (* (fwidths 0) col-unit))]
                                   [width-el  :width (+ (* (dec (fwidths 1)) 3)
                                                        (* (fwidths 1)  col-unit))]))))))))))


(defn- show [sel]
  (dom/remove-class! (sel1 sel) :hidden))

(defn- hide [sel]
  (dom/add-class! (sel1 sel) :hidden))

(defn show-edit-buttons []
  (hide :.btn-save)
  (applies show
           [:.btn-update]
           [:.btn-preview]))

(defn show-loading []
  (show :.blackout-overlay))

(defn hide-loading []
  (hide :.blackout-overlay))

(defn make-preview
  []
  (let [preview-el (sel1 :.preview-pane)
        add-image (node [:a.btn.btn-shoelace.add-image "img"])
        add-text (node [:a.btn.btn-shoelace.add-text "txt"])
        tools (node [:.preview-tools add-image add-text])]
    (comment dom/listen! [preview-el :.wrap] :click
                 (fn [e]
                   (let [col (aget e "selectedTarget")
                         row-id (keyword (dom/attr col :row))
                         col-id (keyword (dom/attr col :col))
                         path [row-id col-id]
                         cur (or (get-in @preview-data path) [])
                         src (aget e "srcElement")]
                     (dom/append! col tools)

                     (when (dom/has-class? src :add-text)
                       (swap! preview-data assoc-in path
                              (conj cur {:type :text :id (new-id! "text")}))
                       (dom/append! col (node [:.preview-text]))
                       (spy @preview-data))

                     (when (dom/has-class? src :preview-text)
                       (spy [:ADD-MORE-TEXT]))

                     (when (dom/has-class? src :add-image)
                       (swap! preview-data assoc-in path
                              (conj cur {:type :img :id (new-id! "img")}))
                       (dom/append! col (node [:.preview-image]))))))))

(defn draw-workspace []
  (let [workspace (sel1 :.workspace)
        output (sel1 :pre.output.lang-html)
        output-less (sel1 :pre.output.lang-text)
        copy-output-el (sel1 :.copy-output)
        container (node [:.sl-container])
        rows (node [:.rows])
        columns (node [:.columns])
        new-row (node [:.sl-row.new-row])
        media-mode (:media-mode @settings)
        media-previews-chan (make-media-previews)
        copy-code-el (sel1 :.copy-code)
        meta-el (sel1 :.meta)
        app-frame (sel1 :.application-frame)
        preview-el (sel1 :.preview-pane)
        preview-button (sel1 :.btn-preview)
        output-preview (sel1 :.output-preview)
        edit-button (sel1 :.btn-edit)
        resize-preview (fn []
                         (dom/set-px! app-frame :margin-top (spy  (- (dom/px app-frame :height)))))

        start-using-less-mixin (fn []
                                 (let [new-layout (vec (for [row @layout]
                                                         (let [cols (for [col (:cols row)]
                                                                      (if (not (:name col))
                                                                        (assoc col
                                                                          :name (str "col-" (:pos row) "-" (:pos col))
                                                                          :from-mixin true)
                                                                        col))]

                                                           (if (not (:name row))
                                                             (assoc row
                                                               :name (str "row-" (:pos row))
                                                               :from-mixin true
                                                               :cols (vec cols))
                                                             (assoc row :cols (vec cols))))))]
                                   (when (not= new-layout @layout)
                                     (reset! layout new-layout)))
                                 (dom/remove-class! output-less :hidden))
        stop-using-less-mixin (fn []
                                (let [new-layout (vec (for [row @layout]
                                                        (let [cols (for [col (:cols row)]
                                                                     (if (:from-mixin col)
                                                                       (assoc col
                                                                         :name false
                                                                         :from-mixin false)
                                                                       col))]
                                                          (if (:from-mixin row)
                                                            (assoc row
                                                              :name false
                                                              :from-mixin false
                                                              :cols (vec cols))
                                                            (assoc row :cols (vec cols))))))]
                                  (when (not= new-layout @layout)
                                    (reset! layout new-layout)))
                                (dom/add-class! output-less :hidden))
        update-output (fn []
          (let [mode (:output-mode @settings)
                use-less-mixin (:use-less-mixin @settings)
                include-container (:include-container @settings)
                code (condp = mode
                       :html (let [layout-html (grid/layout->html @layout (fn []  "") use-less-mixin)]
                               (js/html_beautify
                                (hrt/render-html
                                 (if include-container
                                   (conj [:div.container] layout-html)
                                   layout-html))))
                       :jade (layout->jade @layout include-container use-less-mixin)
                       :edn  (layout->edn @layout))]

            (when use-less-mixin
              (dom/set-text! output-less (layout->less-mixin @layout)))

            (dom/set-html! output-preview (edn->html @layout))

            (dom/remove-class! output :prettyprinted)
            (if (= mode :edn)
              (do
                (dom/set-html! output "")
                (dom/append! output (node (rows->html code))))
              (do (dom/set-text! output (str code))
                  (js/PR.prettyPrint)))
            (aset copy-output-el "value" code)))]

    (stop-using-less-mixin)

    (make-preview)
    (make-options)

    (make-collapse-pane
     :medias-collapsed
     workspace
     (sel1 :.navigator)
     (sel1 [:.navigator :.collapse-panel]))

    (make-collapse-pane
     :output-collapsed
     workspace
     (sel1 :.html)
     (sel1 [:.html :.collapse-panel.right]))

    (dom/add-class! container media-mode)

    (doseq [i (range grid-cols)]
      (let [col (node [:.col])]
        (dom/append! columns col)))

    (doseq [size sizes]
      (let [media (sel1 (str ".preview." (name size)))]
        (when (= size media-mode) (dom/add-class! media :active))
        (dom/listen! media :mouseup #(swap! settings assoc :media-mode size))))


    (add-watch layout :update-output
               (fn [k r os ns]
                 (update-output)
                 (put! media-previews-chan [:update])))

    (applies (partial watch-change settings)
             [:media-mode
              (fn [old-mode new-mode]
                (applies dom/remove-class!
                         [container old-mode]
                         [(sel1 :.preview.active) :active])
                (applies dom/add-class!
                         [(sel1 (str ".preview." (name new-mode))) :active]
                         [container new-mode])
                (update-cols-for-media new-mode)
                (aset workspace "scrollTop" 0))]

             [:output-mode
              (fn [ov nv]
                (update-output))]

             [:use-less-mixin
              (fn [ov nv]
                (if nv
                  (start-using-less-mixin)
                  (stop-using-less-mixin))

                (update-output))]

             [:include-container
              (fn [ov nv]
                (update-output))])

    (update-output)

    (applies dom/listen!
             [meta-el :mouseenter #(dom/add-class! meta-el :left-hand-path)]

             [meta-el :mouseleave #(dom/remove-class! meta-el :left-hand-path)]

             [copy-code-el :click
              (fn []
                (.select copy-output-el)
                (dom/listen-once! body :keyup
                                  (fn [] (spy [:KEYUP :now-hide-popover]))))]

             [new-row :click add-row!]

             [body :mousedown
              (fn [e]
                (set-active-row! :none))]

             [preview-button :click
              (fn []
                (dom/remove-class! preview-el :hidden)
                (dom/listen! js/window :resize resize-preview)
                (resize-preview)
                (dom/add-class! preview-button :hidden)
                (dom/remove-class! edit-button :hidden))]

             [edit-button :click
              (fn []
                (dom/unlisten! js/window :resize resize-preview)
                (dom/listen-once! app-frame transition-end #(dom/add-class! preview-el :hidden))
                (dom/set-px! app-frame :margin-top 0)
                (dom/remove-class! preview-button :hidden)
                (dom/add-class! edit-button :hidden))]

             [(sel1 :.btn-save) :click
              (fn []
                (show-loading)
                (gist/create "shoelace grid"
                             (str (layout->edn @layout))
                             (fn [r]
                               (let [new-id (aget r "id")]
                                 (swap! settings assoc :gist-id (gist/encode-id new-id))
                                 (aset js/window.location "hash" (:gist-id @settings))
                                 (hide-loading)
                                 (show-edit-buttons)))))]

             [(sel1 :.btn-update) :click
              (fn []
                (show-loading)
                (gist/update (:gist-id @settings)
                             "shoelace grid"
                             (str (layout->edn @layout))
                             (fn [r]
                               (let [new-id (aget r "id")]
                                 (hide-loading)
                                 (swap! settings assoc :gist-id (gist/encode-id new-id))
                                 (aset js/window.location "hash" (:gist-id @settings))))))]

             [(sel1 :.btn-gist) :click
              (fn [e]
                (gist/create "shoelace layout" (str (layout->edn @layout)) (fn [r] (js/console.log r))))])

    (applies dom/append!
             [container columns rows]
             [rows new-row]
             [workspace container])))

(defn import-layout
  [layout-str]
  (let [data (read-string layout-str)
        new-row-el (sel1 :.new-row)]
    (if (valid-layout? data)
      (doseq [data-row data]
        (let [row (edn->row data-row)
              [duped-row-id duped-row-el duped-cols-el duped-new-col-el duped-name-el] (create-row)]
          (dom/insert-before! duped-row-el new-row-el)
          (swap! layout conj
                 {:id duped-row-id
                  :pos (count @layout)
                  :cols []
                  :name false})

          (when (:name row)
            (let [duped-row (get-row duped-row-id)]
              (swap! layout assoc-in [(:pos duped-row) :name] (:name row))
              (aset duped-name-el "value" (:name row))))

          (let [new-row (get-row duped-row-id)
                col-unit (calc-col-unit)]
            (doseq [col (:cols row)]
              (let [[new-col-id new-col-el new-col-in-chan els col-name-el]
                      (add-col! false duped-cols-el duped-new-col-el duped-row-id)
                    path [(:pos new-row) :cols (:pos col)]]
                (dom/add-class! (:width  els) :easing)
                (dom/add-class! (:offset els) :easing)
                (swap! layout assoc-in (conj path :name) (:name col))
                (when (:name col)
                  (aset col-name-el "value" (:name col)))
                (doseq [size sizes]
                  (when (size col)
                    (applies dom/set-px!
                             [(:offset els) :width (* col-unit ((size col) 0))]
                             [(:width els)  :width (- (* col-unit ((size col) 1))
                                                      col-margin-width)])
                    (swap! layout assoc-in (conj path size) (size col))))
                (dom/add-class! duped-new-col-el :hidden)
                (put! new-col-in-chan [:draw-classes]))))))
      (spy [:BAD]))
    (update-cols-for-media :sm)))

(defn load-gist [handler]
  (let [gist-id (aget js/window.location "hash")]
    (if (> (count gist-id) 0)
      (let [encoded-id (subs gist-id 1)
            id (gist/decode-id encoded-id)]
        (gist/fetch id #(handler encoded-id id %)))
      (hide-loading))))

(defn load-workspace []
  (load-gist (fn [encoded-id id content]
               (show-edit-buttons)
               (swap! settings assoc :gist-id encoded-id)
               (import-layout (aget content "files" "grid.edn" "content"))
               (hide-loading))))

(if (= (aget js/window.location "pathname") "/preview/")
  (do (load-gist (fn [encoded-id id content]
                   (js/console.log content)
                   (dom/set-html! (sel1 ".output-preview")
                                  (grid/edn-string->html (aget content "files" "grid.edn" "content")))

                   (let [rand-span #(node [:span (str (join "" (range 1 (rand 8))) " ")])
                         start-text (fn [el]
                                      (let [interval (.setInterval js/window #(dom/append! (sel1 el :.wrap) (rand-span)) 15)]
                                        (dom/listen-once! body :mouseup #(.clearInterval js/window interval))))
                         start-remv (fn [el]
                                      (let [interval (.setInterval js/window
                                                                   (fn []
                                                                     (let [spans (sel el :span)]
                                                                       (when (> (count spans) 0)
                                                                         (dom/remove! (last spans)))))
                                                                   15)]
                                        (dom/listen-once! body :mouseup #(.clearInterval js/window interval))))]
                     (applies (partial dom/listen! [body :.row :div])
                              [:mousedown  (fn [e]
                                             (let [el (aget e "selectedTarget")
                                                   width (dom/px el :width)
                                                   left  (aget e "offsetX")]
                                               (if (> left (/ width 2))
                                                 (start-text el)
                                                 (start-remv el))))])))))
  (do
    (draw-workspace)
    (load-workspace)))
