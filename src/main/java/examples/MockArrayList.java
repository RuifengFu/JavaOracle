package examples;

import java.util.ArrayList;
import java.util.Collection;

public class MockArrayList<E> extends ArrayList<E> {

    @Override
    public boolean addAll(Collection<? extends E> c) {
        // Mock 的逻辑：尽量接近源码实现
        Object[] a = c.toArray(); // 将集合转换为数组
        int numNew = a.length; // 新元素的数量
        if (numNew == 0) {
            return false; // 如果集合为空，返回 false
        }

        // 模拟扩容逻辑（假设容量足够，无需扩容）
        int s = size(); // 当前列表大小
        Object[] elementData = new Object[s + numNew]; // 模拟内部数组
        System.arraycopy(a, 0, elementData, s, numNew); // 复制新元素到内部数组

        // 更新列表大小
        for (Object element : a) {
            super.add((E) element); // 调用父类的 add 方法添加元素
        }
        return true; // 返回 true 表示添加成功
    }
}