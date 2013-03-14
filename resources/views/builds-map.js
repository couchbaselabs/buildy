function map(doc, meta) {
    if(meta.type == 'json' && doc.userdata &&
       doc.userdata.product && doc.userdata.product == 'couchbase-server') {
        var build = doc.userdata;
        //Should maybe have that thing have a type indicator?
        emit(["version", build.fullversion]);
        emit(["date", doc.modified]);
    }
}
