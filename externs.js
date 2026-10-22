var git = {};
git.walk = function() {};
git.walkBeta1 = function() {};
git.TREE = function() {};
git.log = function() {};
var fs = {};
fs.unlink = function() {};
fs.readdir = function() {};
fs.rmdir = function() {};
fs.rimraf = function() {};
fs.lstat = function () {};

var dummy = {};
dummy.populateStat = function() {};
dummy.populateHash = function() {};
dummy.oid = function() {};
dummy.fullpath = function() {};
dummy.getRangeAt = function() {};
dummy.getElementsByClassName = function() {};
dummy.containsNode = function() {};
dummy.select = function() {};
dummy.search = function() {};
dummy.add = function() {};
dummy.remove = function() {};
dummy.closest = function () {};
dummy.setAttribute = function() {};
dummy.getAttribute = function() {};
dummy.font = function() {};
dummy.measureText = function() {};
dummy.fillStyle = function() {};
dummy.fillRect = function() {};
dummy.filltextAlign = function() {};
dummy.textBaseLine = function() {};
dummy.fillText = function() {};
dummy.beginPath = function() {};
dummy.arc = function() {};
dummy.fill = function() {};
dummy.getData = function() {};
dummy.zoomToFit = function() {};
dummy.folder = function() {};
dummy.file = function() {};
dummy.generateAsync = function() {};
dummy.showOpenFilePicker = function() {};
dummy.showDirectoryPicker = function() {};
dummy.getDirectoryHandle = function() {};
dummy.getFileHandle = function() {};
dummy.removeEntry = function() {};
dummy.getFile = function() {};
dummy.text = function() {};
dummy.requestPermission = function() {};
dummy.queryPermission = function() {};
dummy.verifyPermission = function() {};
dummy.createWritable = function() {};
dummy.write = function() {};
dummy.close = function() {};
dummy.values = function() {};
// Do we really need those?
dummy.filter = function() {};
dummy.concat = function() {};
dummy.diff_main = function() {};
dummy.patch_make = function() {};
dummy.patch_apply = function() {};
dummy.prepare = function() {};
dummy.run = function() {};
dummy.all = function() {};
dummy.transaction = function() {};
dummy.getPath = function() {};
dummy.getDoc = function() {};
dummy.setValue = function() {};
dummy.data = function() {};
dummy.triangle = function() {};
dummy.vee = function() {};
dummy.destroy = function() {};
dummy.changeData = function() {};
dummy.layout = function() {};
dummy.render = function() {};
dummy.get = function() {};
dummy.addItem = function() {};
dummy.removeItem = function() {};
dummy.resetNodeStyle = function() {};
dummy.forEachNeighbor = function() {};
dummy.graph = function() {};
dummy.forEachEdge = function() {};
dummy.resetEdgeStyle = function() {};
dummy.getNodeAttributes = function() {};
dummy.setNodeAttribute = function() {};
dummy.resetView = function() {};
dummy.destroy = function() {};
dummy.size = function() {};
dummy.id = function() {};
dummy.color = function() {};
dummy.TEXT = function() {};
dummy.TextType = function() {};
dummy.attr = function() {};
dummy.force = function() {};
dummy.distance = function() {};
dummy.links = function() {};
dummy.distanceMax = function() {};
dummy.theta = function() {};
dummy.strength = function() {};
dummy.radius = function() {};
dummy.tick = function() {};
dummy.stop = function() {};
dummy.addNode = function() {};
dummy.addEdge = function() {};
dummy.source = function() {};
dummy.target = function() {};
dummy.PixiGraph = function() {};
dummy.createGraphWithGraph = function() {};
dummy.resetView = function() {};
dummy.dropNode = function() {};
dummy.dropEdge = function() {};
dummy.unhoverNode = function() {};

dummy.bounding = function() {};
dummy.getPageView = function() {};
dummy.convertToPdfPoint = function() {};
dummy.scrollPageIntoView = function() {};
dummy.textLayer = function() {};
dummy.viewport = function() {};
dummy.convertToViewportRectangle = function() {};
dummy.init = function() {};
dummy.commit = function() {};
dummy.raw = function() {};
dummy.onHeadersReceived = function() {};
dummy.responseHeaders = function() {};
dummy.velocityDecay = function() {};
dummy.velocityDecay = function() {};
dummy.updatePosition = function() {};
dummy.getNodesObjects = function() {};
dummy.getEdgesObjects = function() {};
dummy.alphaTarget = function() {};
dummy.restart = function() {};
dummy.observe = function() {};
dummy.contentRect = function() {};
dummy.height = function() {};
dummy.createShapes = function() {};
dummy.updateShapes = function() {};
// hickory related, ATTRIBUTE will be $ATTRIBUTE$
dummy.ATTRIBUTE = function() {};
dummy.COMMENT = function() {};
dummy.DOCUMENT = function() {};
dummy.DOCUMENT_TYPE = function() {};
dummy.ELEMENT = function() {};
dummy.TEXT = function() {};
dummy.isAbsolute = function() {};
dummy._address = function() {};
dummy.setInterfaceStyle = function() {};
dummy.Consumer = {}

var utils = {}
utils.withFileTypes = true;
utils.accessTime = 0;
utils.modifiedTime = 0;
utils.changeTime = 0;
utils.birthTime = 0;
utils.atimeMs = 0;
utils.mtimeMs = 0;
utils.ctimeMs = 0;
utils.birthtimeMs = 0;

/**
 * @typedef {{
 *     recursive: (undefined | boolean),
 * }}
 */
var openDirectoryOptions;
/**
 * @param {(undefined | openDirectoryOptions)} options
 * @param {function} cb
 */
var openDirectory = function(options, cb) {};
var StatusBar;
StatusBar.setStyle = {}
StatusBar.setBackgroundColor = {}
StatusBar.setOverlaysWebView = {}

/**
 * React / ReactDOM are loaded as globals via <script> tags
 * (static/js/react.production.min.js, react-dom.production.min.js) and
 * shadow-cljs resolves the "react"/"react-dom" npm requires to these globals
 * (:js-options :resolve {:target :global}). Property names accessed on them
 * must therefore never be renamed by :advanced compilation.
 *
 * These explicit externs replace the protection that the legacy
 * externs/app.txt (removed in PR #65) used to provide via shadow-cljs's
 * simplified-externs mechanism: while a property name is visible in any
 * externs source, shadow-cljs's infer-externs SKIPS recording cljs-side
 * js/React.* accesses (shadow.build.cljs-hacks/infer-externs-dot), and that
 * skip persists in the cljs analyzer cache. Removing the externs source
 * afterwards leaves the names unprotected and :advanced renames e.g.
 * React.useRef to React.$useRef$, crashing the packaged app.
 */
var React = {};
React.version;
React.Children = {};
React.Children.map = function() {};
React.Children.forEach = function() {};
React.Children.count = function() {};
React.Children.only = function() {};
React.Children.toArray = function() {};
React.Component = function() {};
React.Component.prototype.setState = function() {};
React.Component.prototype.forceUpdate = function() {};
React.PureComponent = function() {};
React.Fragment;
React.StrictMode;
React.Suspense;
React.SuspenseList;
React.Element;
React.createElement = function() {};
React.cloneElement = function() {};
React.createFactory = function() {};
React.createRef = function() {};
React.createContext = function() {};
React.forwardRef = function() {};
React.lazy = function() {};
React.memo = function() {};
React.isValidElement = function() {};
React.startTransition = function() {};
React.unstable_act = function() {};
React.useCallback = function() {};
React.useContext = function() {};
React.useDebugValue = function() {};
React.useDeferredValue = function() {};
React.useEffect = function() {};
React.useId = function() {};
React.useImperativeHandle = function() {};
React.useInsertionEffect = function() {};
React.useLayoutEffect = function() {};
React.useMemo = function() {};
React.useReducer = function() {};
React.useRef = function() {};
React.useState = function() {};
React.useSyncExternalStore = function() {};
React.useTransition = function() {};
React.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED;
/** object returned by React.useRef / React.createRef */
React.RefObject = function() {};
React.RefObject.prototype.current;

var ReactDOM = {};
ReactDOM.version;
ReactDOM.render = function() {};
ReactDOM.hydrate = function() {};
ReactDOM.unmountComponentAtNode = function() {};
ReactDOM.findDOMNode = function() {};
ReactDOM.createPortal = function() {};
ReactDOM.flushSync = function() {};
ReactDOM.unstable_batchedUpdates = function() {};
ReactDOM.createRoot = function() {};
ReactDOM.hydrateRoot = function() {};

var ReactDOMServer = {};
ReactDOMServer.renderToString = function() {};
ReactDOMServer.renderToStaticMarkup = function() {};
ReactDOMServer.renderToNodeStream = function() {};
ReactDOMServer.renderToStaticNodeStream = function() {};

var ReactNative = {};
ReactNative.unstable_batchedUpdates = function() {};
