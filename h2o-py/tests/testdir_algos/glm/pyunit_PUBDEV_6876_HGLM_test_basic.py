from builtins import range
import sys
sys.path.insert(1,"../../../")
import h2o
from tests import pyunit_utils
from h2o.estimators.glm import H2OGeneralizedLinearEstimator

def test_prostate():

    h2o_data = h2o.import_file(path=pyunit_utils.locate("smalldata/glm_test/semiconductor.csv"), 
                               col_types=["enum", "numeric", "numeric", "numeric", "numeric", "numeric", "numeric", 
                                          "numeric"])
    y = "y"
    x = ["x1", "x3", "x5", "x6"]
    h2o_glm = H2OGeneralizedLinearEstimator(HGLM=True, family="gaussian", rand_family=["gaussian"], random_columns=[0], calc_like=True)
    h2o_glm.train(x=x, y=y, training_frame=h2o_data)
    print(h2o_glm)

if __name__ == "__main__":
    pyunit_utils.standalone_test(test_prostate)
else:
    test_prostate()
