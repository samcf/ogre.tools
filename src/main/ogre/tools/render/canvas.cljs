(ns ogre.tools.render.canvas
  (:require [clojure.string :as string :refer [join]]
            [datascript.core :refer [squuid]]
            [ogre.tools.geom :refer [chebyshev euclidean triangle]]
            [ogre.tools.render :refer [css use-image]]
            [ogre.tools.render.draw :refer [draw]]
            [ogre.tools.render.pattern :refer [pattern]]
            [ogre.tools.state :refer [use-query]]
            [ogre.tools.vec :as vec]
            [react-draggable]
            [uix.core.alpha :as uix]))

(def draw-modes
  #{:ruler :circle :rect :cone :line :poly :mask})

(def atmosphere
  {:none     [1.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0]
   :dusk     [0.3 0.3 0.0 0.0 0.0 0.0 0.3 0.3 0.0 0.0 0.0 0.0 0.8 0.0 0.0 0.0 0.0 0.0 1.0 0.0]
   :midnight [0.0 0.0 0.0 0.0 0.0 0.0 0.1 0.0 0.0 0.0 0.1 0.1 0.1 0.0 0.0 0.0 0.0 0.0 1.0 0.0]})

(defn ft->px [ft size] (* (/ ft 5) size))

(defn xf [& kvs]
  (let [xform (comp
               (partition-all 2)
               (map (fn [[k v]]
                      (case k
                        :scale (str "scale(" v ")")
                        :translate (let [[x y] v] (str "translate(" x ", " y ")"))))))]
    (join " " (into [] xform kvs))))

(defn text [attrs child]
  [:text.canvas-text attrs child])

(defn visible? [flags]
  (or (nil? flags)
      (flags :player)
      (not (some flags [:hidden :invisible]))))

(defn label [{:keys [element/name initiative/suffix]}]
  (cond-> ""
    (string? name) (str name)
    (number? suffix) (str " " (char (+ suffix 64)))))

(def scene-query
  {:pull
   [{:root/canvas
     [[:canvas/color :default :none]
      {:canvas/scene
       [:image/checksum]}]}]})

(defn scene []
  (let [[result] (use-query scene-query)
        {{color :canvas/color
          {checksum :image/checksum} :canvas/scene} :root/canvas} result
        url (use-image checksum)]
    [:g.canvas-image
     [:defs {:key color}
      [:filter {:id "atmosphere"}
       [:feColorMatrix {:type "matrix" :values (join " " (atmosphere color))}]]]
     [:image {:x 0 :y 0 :href url :style {:filter "url(#atmosphere)"}}]]))

(def light-mask-query
  {:pull
   [:root/host?
    {:root/canvas
     [[:canvas/visibility :default :revealed]
      [:grid/size :default 70]
      {:canvas/tokens
       [:db/id
        [:element/flags :default #{}]
        [:token/light :default 15]
        [:pos/vec :default [0 0]]]}
      {:canvas/scene
       [:image/checksum
        :image/width
        :image/height]}]}]})

(defn light-mask []
  (let [[result] (use-query light-mask-query)
        {host? :root/host?
         {visibility :canvas/visibility
          tokens     :canvas/tokens
          size       :grid/size
          {checksum :image/checksum
           width    :image/width
           height   :image/height} :canvas/scene} :root/canvas} result]
    (if (and checksum (not= visibility :revealed))
      [:g.canvas-mask {:css {:is-dimmed (= visibility :dimmed)}}
       [:defs
        [pattern {:id "mask-pattern" :name :lines}]
        [:radialGradient {:id "mask-gradient"}
         [:stop {:offset "0%" :stop-color "black" :stop-opacity "100%"}]
         [:stop {:offset "70%" :stop-color "black" :stop-opacity "100%"}]
         [:stop {:offset "100%" :stop-color "black" :stop-opacity "0%"}]]
        [:mask {:id "light-mask"}
         [:rect {:x 0 :y 0 :width width :height height :fill "white" :fill-opacity "100%"}]
         (for [{id :db/id flags :element/flags [x y] :pos/vec radius :token/light} tokens
               :when (and (> radius 0) (or host? (visible? flags)))]
           ^{:key id} [:circle {:cx x :cy y :r (+ (ft->px radius size) (/ size 2)) :fill "url(#mask-gradient)"}])]]
       [:rect.canvas-mask-background
        {:x 0 :y 0 :width width :height height :mask "url(#light-mask)"}]
       (if (= visibility :hidden)
         [:rect.canvas-mask-pattern
          {:x 0 :y 0 :width width :height height
           :fill "url(#mask-pattern)" :mask "url(#light-mask)"}])])))

(def canvas-mask-query
  {:pull
   [:root/host?
    {:root/canvas
     [[:canvas/mode :default :select]
      [:mask/filled? :default false]
      {:canvas/scene [:image/width :image/height]}
      {:canvas/masks [:db/id :mask/vecs :mask/enabled?]}]}]})

(defn canvas-mask []
  (let [[result dispatch] (use-query canvas-mask-query)
        {host?    :root/host?
         {filled? :mask/filled?
          masks   :canvas/masks
          mode    :canvas/mode
          {width  :image/width
           height :image/height} :canvas/scene} :root/canvas} result
        modes #{:mask :mask-toggle :mask-remove}]
    [:g.canvas-mask
     [:defs
      [pattern {:id "mask-pattern" :name :lines}]
      [:mask {:id "canvas-mask"}
       (if filled?
         [:rect {:x 0 :y 0 :width width :height height :fill "white"}])
       (for [{id :db/id enabled? :mask/enabled? xs :mask/vecs} masks]
         ^{:key id} [:polygon {:points (join " " xs) :fill (if enabled? "white" "black")}])]]
     [:rect.canvas-mask-background {:x 0 :y 0 :width width :height height :mask "url(#canvas-mask)"}]
     [:rect.canvas-mask-pattern {:x 0 :y 0 :width width :height height :fill "url(#mask-pattern)" :mask "url(#canvas-mask)"}]
     (if (and host? (contains? modes mode))
       (for [{id :db/id xs :mask/vecs enabled? :mask/enabled?} masks]
         ^{:key id}
         [:polygon.canvas-mask-polygon
          {:data-enabled enabled?
           :points (join " " xs)
           :on-click
           (fn []
             (case mode
               :mask-toggle (dispatch :mask/toggle id (not enabled?))
               :mask-remove (dispatch :mask/remove id)))}]))]))

(def grid-query
  {:pull
   [:bounds/self
    {:root/canvas
     [[:canvas/mode :default :select]
      [:pos/vec :default [0 0]]
      [:grid/size :default 70]
      [:grid/show :default true]
      [:zoom/scale :default 1]]}]})

(defn grid []
  (let [[data] (use-query grid-query)
        {[_ _ w h] :bounds/self
         {mode    :canvas/mode
          size    :grid/size
          show    :grid/show
          scale   :zoom/scale
          [cx cy] :pos/vec} :root/canvas} data]
    (if (or show (= mode :grid))
      (let [w (/ w scale)
            h (/ h scale)
            [sx sy ax ay bx]
            [(- (* w -3) cx)
             (- (* h -3) cy)
             (- (* w  3) cx)
             (- (* h  3) cy)
             (- (* w -3) cx)]]
        [:g {:class "canvas-grid"}
         [:defs
          [:pattern {:id "grid" :width size :height size :patternUnits "userSpaceOnUse"}
           [:path
            {:d (string/join " " ["M" 0 0 "H" size "V" size])}]]]
         [:path {:d (string/join " " ["M" sx sy "H" ax "V" ay "H" bx "Z"]) :fill "url(#grid)"}]]))))

(defmulti shape (fn [props] (:shape/kind (:element props))))

(defmethod shape :circle [props]
  (let [{:keys [element attrs]} props
        {:keys [shape/vecs shape/color shape/opacity]} element
        [ax ay bx by] vecs]
    [:circle
     (merge
      attrs
      {:cx 0 :cy 0 :r (chebyshev ax ay bx by)
       :fill-opacity opacity :stroke color})]))

(defmethod shape :rect [props]
  (let [{:keys [element attrs]} props
        {:keys [shape/vecs shape/color shape/opacity]} element
        [ax ay bx by] vecs]
    [:path
     (merge
      attrs
      {:d (string/join " " ["M" 0 0 "H" (- bx ax) "V" (- by ay) "H" 0 "Z"])
       :fill-opacity opacity :stroke color})]))

(defmethod shape :line [props]
  (let [{:keys [element]} props
        {:keys [shape/vecs shape/color]} element
        [ax ay bx by] vecs]
    [:line {:x1 0 :y1 0 :x2 (- bx ax) :y2 (- by ay) :stroke color :stroke-width 4 :stroke-linecap "round"}]))

(defmethod shape :cone [props]
  (let [{:keys [element attrs]} props
        {:keys [shape/vecs shape/color shape/opacity]} element
        [ax ay bx by] vecs]
    [:polygon
     (merge
      attrs
      {:points (string/join " " (triangle 0 0 (- bx ax) (- by ay)))
       :fill-opacity opacity :stroke color})]))

(defn poly-xf [x y]
  (comp (partition-all 2)
        (mapcat (fn [[ax ay]] [(- ax x) (- ay y)]))))

(defmethod shape :poly [props]
  (let [{:keys [element attrs]} props
        {:keys [shape/vecs shape/color shape/opacity]} element
        [ax ay] (into [] (take 2) vecs)
        pairs   (into [] (poly-xf ax ay) vecs)]
    [:polygon (assoc attrs :points (join " " pairs) :fill-opacity opacity :stroke color)]))

(def shapes-query
  {:pull
   '[{:root/canvas
      [[:zoom/scale :default 1]
       [:grid/align :default false]
       {:canvas/shapes
        [:db/id
         :element/name
         :shape/kind
         :shape/vecs
         [:shape/color :default "#f44336"]
         [:shape/opacity :default 0.25]
         [:shape/pattern :default :solid]
         :canvas/_selected]}]}]})

(defn shapes []
  (let [[result dispatch] (use-query shapes-query)]
    (for [element (-> result :root/canvas :canvas/shapes)]
      (let [{id :db/id [ax ay] :shape/vecs} element]
        [:> react-draggable
         {:key      id
          :scale    (-> result :root/canvas :zoom/scale)
          :position #js {:x ax :y ay}
          :on-start (fn [event] (.stopPropagation event))
          :on-stop
          (fn [event data]
            (let [ox (.-x data) oy (.-y data)]
              (if (> (euclidean ax ay ox oy) 0)
                (dispatch :shape/translate id ox oy (not= (.-metaKey event) (-> result :root/canvas :grid/align)))
                (dispatch :element/select id))))}
         (let [id (squuid)]
           [:g
            {:css
             {:canvas-shape true
              :selected (:canvas/_selected element)
              (str "canvas-shape-" (name (:shape/kind element))) true}}
            [:defs [pattern {:id id :name (:shape/pattern element) :color (:shape/color element)}]]
            [shape {:element element :attrs {:fill (str "url(#" id ")")}}]])]))))

(defn token-query [id]
  {:query
   '[:find (pull $ ?id pattern) . :in $ ?id pattern]

   :pull
   [:element/name
    [:element/flags :default #{}]
    [:token/size :default {:name :medium :size 5}]
    {:token/stamp [:image/checksum]}
    :aura/label
    [:aura/radius :default 0]
    :initiative/suffix
    :canvas/_selected
    {:canvas/_tokens [[:grid/size :default 70]]}]

   :args
   [id]})

(defn token [props]
  (let [[data] (use-query (token-query (:id props)))
        flags  (:element/flags data)
        class  (->> flags
                    (mapv #(str "flag--" (name %)))
                    (css "canvas-token" {:selected (:canvas/_selected data)}))
        size   (-> data :canvas/_tokens :grid/size)
        radius (-> data :token/size :size (ft->px size) (/ 2) (- 2) (max 16))]
    [:g {:class class}
     (if (> (:aura/radius data) 0)
       (let [radius (+ (ft->px (:aura/radius data) size) (/ size 2))]
         [:<>
          [:circle.canvas-token-aura {:cx 0 :cy 0 :r radius}]
          (if (seq (:aura/label data))
            (let [cx (+ (* (.cos js/Math 0.75) radius) 8)
                  cy (+ (* (.sin js/Math 0.75) radius) 8)]
              [text {:x cx :y cy} (:aura/label data)]))]))
     (if (:canvas/_selected data)
       [:circle.canvas-token-ring
        {:cx 0 :cy 0 :style {:r radius :fill "transparent"}}])
     (let [checksum (-> data :token/stamp :image/checksum)
           pattern  (cond
                      (flags :deceased)  "token-stamp-deceased"
                      (string? checksum) (str "token-stamp-" checksum)
                      :else              "token-stamp-default")]
       [:circle.canvas-token-shape
        {:cx 0 :cy 0 :r radius :fill (str "url(#" pattern ")")}])
     (if (seq (label data))
       [text {:x 0 :y (+ radius 10)} (label data)])]))

(defn stamp [{:keys [checksum]}]
  (let [url (use-image checksum)]
    [:image {:href url :width 1 :height 1 :preserveAspectRatio "xMidYMin slice"}]))

(def stamps-query
  {:pull
   [{:root/canvas
     [{:canvas/tokens
       [{:token/stamp [:image/checksum]}]}]}]})

(defn stamps []
  (let [[data] (use-query stamps-query)
        lookup (fn [t] (-> t :token/stamp :image/checksum))
        images (->> data :root/canvas :canvas/tokens (map lookup) set)
        attrs  {:width "100%" :height "100%" :patternContentUnits "objectBoundingBox"}]
    [:defs
     [:pattern (merge attrs {:id "token-stamp-default" :viewBox "0 0 16 16" :fill "#f2f2eb"})
      [:rect {:x 0 :y 0 :width 16 :height 16 :fill "hsl(200, 20%, 12%)"}]
      [:path {:d "M11 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"}]
      [:path {:d "M0 8a8 8 0 1 1 16 0A8 8 0 0 1 0 8zm8-7a7 7 0 0 0-5.468 11.37C3.242 11.226 4.805 10 8 10s4.757 1.225 5.468 2.37A7 7 0 0 0 8 1z" :fill-rule "evenodd"}]]
     [:pattern (merge attrs {:id "token-stamp-deceased" :viewBox "0 0 16 16" :fill "#f2f2eb"})
      [:rect {:x 0 :y 0 :width 16 :height 16 :fill "hsl(200, 20%, 12%)"}]
      [:path {:d "M8 15A7 7 0 1 1 8 1a7 7 0 0 1 0 14zm0 1A8 8 0 1 0 8 0a8 8 0 0 0 0 16z"}]
      [:path {:d "M9.146 5.146a.5.5 0 0 1 .708 0l.646.647.646-.647a.5.5 0 0 1
                  .708.708l-.647.646.647.646a.5.5 0 0 1-.708.708l-.646-.647-.646.647a.5.5
                  0 1 1-.708-.708l.647-.646-.647-.646a.5.5 0 0 1 0-.708zm-5 0a.5.5 0 0 1
                  .708 0l.646.647.646-.647a.5.5 0 1 1 .708.708l-.647.646.647.646a.5.5
                  0 1 1-.708.708L5.5 7.207l-.646.647a.5.5 0 1 1-.708-.708l.647-.646-.647-.646a.5.5
                  0 0 1 0-.708zM10 11a2 2 0 1 1-4 0 2 2 0 0 1 4 0z"}]]
     (for [checksum images]
       [:pattern (merge attrs {:key checksum :id (str "token-stamp-" checksum)})
        [stamp {:checksum checksum}]])]))

(def tokens-query
  {:pull
   [:root/host?
    {:root/canvas
     [[:zoom/scale :default 1]
      [:grid/align :default false]
      {:canvas/tokens
       [:db/id
        [:pos/vec :default [0 0]]
        [:element/flags :default #{}]
        :canvas/_selected]}]}]})

(defn tokens []
  (let [[result dispatch] (use-query tokens-query)]
    (for [entity (-> result :root/canvas :canvas/tokens)
          :let [{id :db/id [ax ay] :pos/vec} entity]
          :when (and (not (:canvas/_selected entity))
                     (or (:root/host? result)
                         (visible? (:element/flags entity))))]
      [:> react-draggable
       {:key      id
        :position #js {:x ax :y ay}
        :scale    (-> result :root/canvas :zoom/scale)
        :on-start (fn [event] (.stopPropagation event))
        :on-stop
        (fn [event data]
          (.stopPropagation event)
          (let [bx (.-x data) by (.-y data)]
            (if (= (euclidean ax ay bx by) 0)
              (dispatch :element/select id (not (.-shiftKey event)))
              (let [align? (not= (.-metaKey event) (-> result :root/canvas :grid/align))]
                (dispatch :token/translate id [bx by] align?)))))}
       [:g.canvas-token
        [token {:id id}]]])))

(def selection-query
  {:pull
   [:root/host?
    {:root/canvas
     [[:grid/size :default 70]
      [:grid/align :default false]
      [:zoom/scale :default 1]
      {:canvas/selected
       [:db/id
        :element/type
        :element/flags
        [:pos/vec :default [0 0]]]}]}]})

(defn selection []
  (let [[result dispatch] (use-query selection-query)
        {:keys [root/host? root/canvas]} result
        {:keys [canvas/selected zoom/scale]} canvas
        idents (map :db/id selected)]
    (if (= (:element/type (first selected)) :token)
      [:> react-draggable
       {:position #js {:x 0 :y 0}
        :scale scale
        :on-start
        (fn [event]
          (.stopPropagation event))
        :on-stop
        (fn [event data]
          (let [ox (.-x data) oy (.-y data)]
            (if (and (= ox 0) (= oy 0))
              (let [id (.. event -target (closest ".canvas-token[data-id]") -dataset -id)]
                (dispatch :element/select (js/Number id) false))
              (dispatch :token/translate-all idents [ox oy] (not= (.-metaKey event) (:grid/align canvas))))))}
       [:g.canvas-selected {:key idents}
        (for [entity selected :when (or host? (visible? (:element/flags entity)))]
          (let [id (:db/id entity)]
            [:g.canvas-token
             {:key id :data-id id :transform (xf :translate (:pos/vec entity))}
             [token {:id id}]]))]])))

(defn bounds []
  (let [[result] (use-query {:pull [:bounds/host :bounds/guest]})
        {[_ _ hw hh] :bounds/host
         [_ _ gw gh] :bounds/guest} result
        [ox oy] [(/ (- hw gw) 2) (/ (- hh gh) 2)]]
    [:g.canvas-bounds {:transform (xf :translate [ox oy])}
     [:rect {:x 0 :y 0 :width gw :height gh :rx 8}]]))

(def canvas-query
  {:pull
   [:root/privileged?
    :root/host?
    :bounds/host
    :bounds/guest
    {:root/canvas
     [[:pos/vec :default [0 0]]
      [:canvas/mode :default :select]
      [:canvas/theme :default :light]
      :canvas/modifier
      [:zoom/scale :default 1]]}]})

(defn canvas []
  (let [select-node       (uix/ref)
        [result dispatch] (use-query canvas-query)
        {priv? :root/privileged?
         host? :root/host?
         [_ _ hw hh] :bounds/host
         [_ _ gw gh] :bounds/guest
         {scale :zoom/scale
          mode  :canvas/mode
          theme :canvas/theme
          modif :canvas/modifier
          coord :pos/vec} :root/canvas} result
        coord (if host? coord
                  (->> [(- hw gw) (- hh gh)]
                       (mapv (partial max 0))
                       (vec/s (/ -1 2 scale))
                       (vec/+ coord)))]
    [:svg.canvas {:css {(str "theme--" (name theme)) true :is-host host? :is-priv priv?}}
     [:> react-draggable
      {:position #js {:x 0 :y 0}
       :on-stop
       (fn [_ data]
         (let [ox (.-x data) oy (.-y data)]
           (if (and (= ox 0) (= oy 0))
             (dispatch :selection/clear)
             (dispatch :camera/translate (vec/+ coord (vec/s (/ scale) [ox oy]))))))}
      [:g {:style {:will-change "transform"}}
       [:rect {:x 0 :y 0 :width "100%" :height "100%" :fill "transparent"}]
       (if (and (= mode :select) (= modif :shift))
         [draw {:mode :select :node select-node}])
       [:g.canvas-board
        {:transform (xf :scale scale :translate coord)}
        [stamps]
        [scene]
        [grid]
        [shapes]
        [tokens]
        [selection]
        [light-mask]
        [canvas-mask]]
       (if (and (= mode :select) (= modif :shift))
         [:g {:ref select-node :class "canvas-drawable canvas-drawable-select"}])
       (if (contains? draw-modes mode)
         [:g {:class (str "canvas-drawable canvas-drawable-" (name mode))}
          ^{:key mode} [draw {:mode mode :node nil}]])]]
     (if priv?
       [bounds])]))
