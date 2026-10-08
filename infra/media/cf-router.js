// CloudFront Function（viewer-request）：落地页与源码页的静态路由。
function handler(event) {
  var r = event.request;
  if (r.uri === '/m' || r.uri.indexOf('/m/') === 0) { r.uri = '/m/index.html'; }
  else if (r.uri === '/p' || r.uri.indexOf('/p/') === 0) { r.uri = '/p/index.html'; }
  else if (r.uri === '/source' || r.uri === '/source/') { r.uri = '/source/index.html'; }
  return r;
}
