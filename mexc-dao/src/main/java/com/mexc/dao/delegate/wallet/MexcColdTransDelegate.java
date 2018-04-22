package com.mexc.dao.delegate.wallet;

import com.mexc.dao.dao.IBaseDAO;
import com.mexc.dao.dao.wallet.MexcColdTransDAO;
import com.mexc.dao.delegate.AbstractDelegate;
import com.mexc.dao.model.wallet.MexcColdTrans;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Describe
 * <p>
 * User: yangguang
 * Date: 2017/12/30
 * Time: 下午10:51
 */
@Transactional
@Component
public class MexcColdTransDelegate extends AbstractDelegate<MexcColdTrans, String> {

    @Autowired
    MexcColdTransDAO mexcColdTransDAO;


    @Autowired
    @Override
    public void setBaseMapper(IBaseDAO<MexcColdTrans, String> baseMapper) {
        super.setBaseMapper(baseMapper);
    }
}
