/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React, { useEffect, useImperativeHandle, useRef, useState } from 'react';
import { Form, Select, Row, Col } from 'antd';
import { API } from '@/services';
import RelationList from '../RelationList';
import { IModelDetail } from '@/pages/DataModel/types';
import Message from 'pages/DataModel/components/Message';
// import { Mode } from 'node:fs';
interface IPropsRelationTableSelect {
  form?: any;
  cref: any;
  modelDetail?: Partial<IModelDetail>;
  mode: any;
  globalStep: number;
  updateModelDetail: Function;
  setDisabled?: Function;
}
const { Option } = Select;

interface TableItem {
  tableName: string;
}

const RelationTableSelect = (props: IPropsRelationTableSelect) => {
  const { form, cref, modelDetail, globalStep, mode, setDisabled } = props;
  const { getFieldDecorator, validateFields, getFieldsValue, setFieldsValue } =
    form;
  const [schemaList, setSchemaList] = useState<string[]>([]);
  const [tableList, setTableList] = useState<TableItem[]>([]);
  const [visibleUpdateType, setVisibleUpdateType] = useState(false);
  const [updateTypeList, setUpdateTypeList] = useState<
    { leftValue: number; rightValue: string }[]
  >([]);
  const refRelationList = useRef(null);
  const isDisabled = mode === 'EDIT' && globalStep >= 1;

  useImperativeHandle(cref, () => {
    return {
      validate: () =>
        new Promise((resolve, reject) => {
          validateFields((err, data) => {
            if (err) return reject(err.message);
            const _value = {
              ...data,
              partition: visibleUpdateType,
            };
            refRelationList.current.validate().then((data) => {
              _value.joinList = data;
              resolve(_value);
            });
          });
        }),
      getValue: () => {
        const _value = getFieldsValue();
        const relationList = refRelationList.current.getValue();
        _value.joinList = relationList.map((item) => {
          const leftTable = item.leftTable;
          return {
            ...item,
            leftTable,
          };
        });
        return _value;
      },
    };
  });

  const currentFormValue = getFieldsValue();

  const getSchemaList = async (dsId: number) => {
    if (!dsId) return;
    try {
      const { success, data, message } = await API.getDataModelSchemaList({
        datasourceId: dsId,
      });
      if (success) {
        setSchemaList(data);
      } else {
        Message.error(message);
      }
    } catch (err) {
      Message.error(err.message);
    }
  };

  // 根据数据源和schema获取表
  const getTableList = async (dsId: number, schema: string) => {
    if (!dsId || !schema) return;
    try {
      const { success, data, message } = await API.getDataModelTableList({
        datasourceId: dsId,
        schema,
      });
      if (success) {
        setTableList(data);
      } else {
        Message.error(message);
      }
    } catch (error) {
      Message.error(error.message);
    }
  };

  const getUpdateTypeList = async () => {
    try {
      const { success, data, message } = await API.getDataModelUpdateTypeList();
      if (success) {
        setUpdateTypeList(data);
      } else {
        Message.error(message);
      }
    } catch (error) {
      Message.error(error.message);
    }
  };

  // 判断表是否为分区表，决定是否显示更新方式
  const isPartition = async (
    dsId: number,
    schema: string,
    tableName: string
  ) => {
    if (!dsId || !schema || !tableName) return;
    setDisabled(true);
    try {
      const { success, data, message } = await API.isPartition({
        datasourceId: dsId,
        schema,
        tableName,
      });
      if (success) {
        setVisibleUpdateType(data);
      } else {
        Message.error(message);
      }
    } catch (error) {
      Message.error(error.message);
    } finally {
      setDisabled(false);
    }
  };

  useEffect(() => {
    getUpdateTypeList();
  }, []);

  useEffect(() => {
    getSchemaList(modelDetail.dsId);
    setVisibleUpdateType(false);
  }, [modelDetail.dsId]);

  useEffect(() => {
    getTableList(modelDetail.dsId, currentFormValue.schema);
  }, [modelDetail.dsId, currentFormValue.schema]);

  useEffect(() => {
    const { schema, tableName } = modelDetail;
    setFieldsValue({
      schema: schema === null ? undefined : schema,
      tableName: tableName === null ? undefined : tableName,
    });
  }, [modelDetail]);

  useEffect(() => {
    if (!visibleUpdateType) return;
    setFieldsValue({
      updateType: modelDetail.updateType,
    });
  }, [visibleUpdateType]);

  useEffect(() => {
    isPartition(
      modelDetail.dsId,
      currentFormValue.schema,
      currentFormValue.tableName
    );
  }, [currentFormValue.tableName]);

  return (
    <div className="padding-top-20" ref={cref}>
      <Form
        className="dm-form"
        labelCol={{ span: 3 }}
        wrapperCol={{ span: 21 }}>
        <Form.Item label="schema">
          {getFieldDecorator('schema', {
            rules: [{ required: true, message: '请选择schema' }],
          })(
            <Select
              className="dm-form-item"
              dropdownClassName="dm-form-select-drop"
              placeholder="请选择schema"
              disabled={isDisabled}
              showSearch
              optionFilterProp="children"
              onChange={(v) => {
                window.localStorage.setItem('refreshColumns', 'true');
                setFieldsValue({
                  ...currentFormValue,
                  tableName: undefined,
                });
                setVisibleUpdateType(false);
              }}>
              {schemaList.map((schema) => (
                <Option key={schema} value={schema}>
                  {schema}
                </Option>
              ))}
            </Select>
          )}
        </Form.Item>
        <Form.Item label="选择表">
          {getFieldDecorator('tableName', {
            rules: [{ required: true, message: '请选择表' }],
          })(
            <Select
              className="dm-form-item"
              dropdownClassName="dm-form-select-drop"
              placeholder="请选择表"
              disabled={isDisabled}
              showSearch
              optionFilterProp="children"
              onChange={() => {
                window.localStorage.setItem('refreshColumns', 'true');
              }}>
              {tableList.map((table) => (
                <Option key={table.tableName} value={table.tableName}>
                  {table.tableName}
                </Option>
              ))}
            </Select>
          )}
        </Form.Item>
        {visibleUpdateType ? (
          <Form.Item label="更新方式">
            {getFieldDecorator('updateType', {
              rules: [{ required: true, message: '请选择更新方式' }],
            })(
              <Select
                dropdownClassName="dm-form-select-drop"
                placeholder="请选择更新方式">
                {updateTypeList.map((updateType) => (
                  <Option
                    key={updateType.leftValue}
                    value={updateType.leftValue}>
                    {updateType.rightValue}
                  </Option>
                ))}
              </Select>
            )}
          </Form.Item>
        ) : null}
      </Form>
      <Row>
        <Col span={24}>
          <RelationList
            cref={refRelationList}
            updateTypeList={updateTypeList}
            modelDetail={{
              ...modelDetail,
              ...getFieldsValue(),
            }}
            updateModelDetail={props.updateModelDetail}
            setDisabled={setDisabled}
          />
        </Col>
      </Row>
    </div>
  );
};

export default Form.create()(RelationTableSelect) as any;