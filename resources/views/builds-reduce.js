function reduce(keys, vals, rereduce) {
  var cats = ['os', 'version', 'arch', 'license', 'toy'];
  var out = {};
  vals.forEach(function(v) {
    cats.forEach(function(cat) {
      if(!out[cat]) {
        out[cat] = {};
      }
      if(!rereduce) {
      	out[cat][v[cat]] = true;
      } else {
        v[cat].forEach(function(el) {
          out[cat][el] = true;
        });
      }
    });
  });
  for(cat in out) {
    var els = [];
    for(el in out[cat]) {
      els.push(el);
    };
    out[cat] = els;
  }
  return out;
}
