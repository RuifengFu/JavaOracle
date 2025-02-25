package examples;

import java.util.Arrays;
import java.util.Collection;
import java.util.Vector;

public class MockVector<E> extends Vector<E> {

    @Override
    public synchronized boolean addAll(Collection<? extends E> c) {
        // Mock 的逻辑：尽量接近源码实现
        Object[] a = c.toArray(); // 将集合转换为数组
        modCount++; // 修改计数器
        int numNew = a.length; // 新元素的数量
        if (numNew == 0) {
            return false; // 如果集合为空，返回 false
        }

        // 模拟扩容逻辑
        Object[] elementData = this.elementData; // 获取内部数组
        final int s = elementCount; // 当前元素数量
        if (numNew > elementData.length - s) {
            elementData = grow(s + numNew); // 扩容
        }

        // 复制新元素到内部数组
        System.arraycopy(a, 0, elementData, s, numNew);
        elementCount = s + numNew; // 更新元素数量
        return true; // 返回 true 表示添加成功
    }

    // 模拟 grow 方法
    private Object[] grow(int minCapacity) {
        int oldCapacity = elementData.length;
        int newCapacity = oldCapacity + Math.max(oldCapacity >> 1, minCapacity - oldCapacity); // 1.5 倍扩容
        return elementData = Arrays.copyOf(elementData, newCapacity);
    }
}