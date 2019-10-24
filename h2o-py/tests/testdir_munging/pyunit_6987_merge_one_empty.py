from __future__ import print_function
import sys
sys.path.insert(1,"../../")
import h2o
from tests import pyunit_utils
import pandas as pd

def mergeOneEmptyFrame():
    # PUBDEV-6987: merge with one empty frame and one normal frame.
    file1 = h2o.H2OFrame({"A1":[1], "A2":[0]})
    file2 = h2o.H2OFrame({"A1":[], "A2":[]})
    f1Mergef2 = file1.merge(file2)
    f2Mergef1 = file2.merge(file1)
    f2Mergef2 = file2.merge(file2)  # merging of empty frame with just headers

    assert f1Mergef2[0,0]==file1[0,0] and f1Mergef2[0,1]==file1[0,1], "Merge file error!"
    assert f2Mergef1[0,0]==file1[0,0] and f1Mergef2[0,1]==file1[0,1], "Merge file error!"   
    assert f2Mergef2.nrow==0, "Merge file error with two empty frames!"
    
if __name__ == "__main__":
    pyunit_utils.standalone_test(mergeOneEmptyFrame)
else:
    mergeOneEmptyFrame()

